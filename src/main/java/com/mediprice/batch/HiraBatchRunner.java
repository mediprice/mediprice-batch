package com.mediprice.batch;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

// 배치 실행기
@Component
public class HiraBatchRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(HiraBatchRunner.class);

    private static final String DEFAULT_JOB = "hiraSyncJob";
    // 실행 기준 시각. 이어서 실행할 때도 처음 값 그대로 씀 (정리 단계의 기준)
    public static final String REQUESTED_AT = "requestedAt";

    // 프로그램이 갑자기 꺼져서 "실행 중"으로 남은 상태
    private static final Set<BatchStatus> STALE = Set.of(BatchStatus.STARTED, BatchStatus.STARTING,
            BatchStatus.STOPPING, BatchStatus.UNKNOWN);

    // 커밋 한 번에 걸리는 시간보다 충분히 길게 잡음
    private static final Duration DEAD_AFTER = Duration.ofMinutes(30);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Map<String, Job> jobs;

    private final Clock clock;

    private int exitCode = 0;

    public HiraBatchRunner(JobOperator jobOperator, JobRepository jobRepository, Map<String, Job> jobs) {
        this(jobOperator, jobRepository, jobs, KoreaTime.clock());
    }

    public HiraBatchRunner(JobOperator jobOperator, JobRepository jobRepository, Map<String, Job> jobs, Clock clock) {
        this.clock = clock;
        this.jobOperator = jobOperator;
        this.jobRepository = jobRepository;
        this.jobs = jobs;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String jobName = args.containsOption("job") ? args.getOptionValues("job").get(0) : DEFAULT_JOB;
        Job job = jobs.get(jobName);
        if (job == null) {
            throw new IllegalArgumentException("없는 Job: " + jobName + " (가능: " + jobs.keySet() + ")");
        }

        // 같은 테이블을 동시에 갱신하지 않게 막음
        if (jobRepository.findRunningJobExecutions(jobName).stream().anyMatch(this::isAlive)) {
            log.warn("{} 다른 실행이 진행 중이라 시작하지 않음", jobName);
            exitCode = 1;
            return;
        }

        JobParameters params = args.containsOption("restart")
                ? lastFailedParameters(jobName)
                : new JobParametersBuilder().addLocalDateTime(REQUESTED_AT, LocalDateTime.now(clock)).toJobParameters();

        // 같은 파라미터의 실패한 실행이 있으면 Spring Batch가 실패한 Step부터 이어서 실행함
        JobExecution execution = jobOperator.start(job, params);
        log.info("{} 종료: {} (파라미터 {})", jobName, execution.getStatus(), params);

        // 실패하면 종료 코드 1 (나중에 자동 실행 도구가 실패를 알 수 있게)
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            exitCode = 1;
        }
    }

    public static LocalDateTime requestedAt(ChunkContext chunkContext) {
        LocalDateTime requestedAt = chunkContext.getStepContext().getStepExecution()
                .getJobParameters().getLocalDateTime(REQUESTED_AT);
        if (requestedAt == null) {
            throw new IllegalStateException("실행 파라미터 requestedAt이 없음 (HiraBatchRunner로 실행해야 함)");
        }
        return requestedAt;
    }

    // "실행 중" 상태만으로는 꺼진 실행과 구분이 안 돼서 마지막 기록 시각으로 판단
    private boolean isAlive(JobExecution execution) {
        LocalDateTime limit = LocalDateTime.now().minus(DEAD_AFTER);
        return isAfter(execution.getLastUpdated(), limit)
                || execution.getStepExecutions().stream().anyMatch(step -> isAfter(step.getLastUpdated(), limit));
    }

    private boolean isAfter(LocalDateTime time, LocalDateTime limit) {
        return time != null && time.isAfter(limit);
    }

    // 마지막 실행이 실패했으면 그 파라미터를 돌려줌. "실행 중"으로 남아 있으면 실패로 정리한 뒤 돌려줌
    private JobParameters lastFailedParameters(String jobName) {
        JobInstance instance = jobRepository.getLastJobInstance(jobName);
        JobExecution last = instance == null ? null : jobRepository.getLastJobExecution(instance);
        if (last == null) {
            throw new IllegalStateException(jobName + " 이어서 실행할 기록이 없음");
        }

        if (STALE.contains(last.getStatus())) {
            log.warn("{} 실행 {}이(가) {} 상태로 남아 있어 실패로 정리함", jobName, last.getId(), last.getStatus());
            last = jobOperator.recover(last);
        }

        if (last.getStatus() != BatchStatus.FAILED && last.getStatus() != BatchStatus.STOPPED) {
            throw new IllegalStateException(jobName + " 마지막 실행이 실패 상태가 아님: " + last.getStatus());
        }
        return last.getJobParameters();
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}

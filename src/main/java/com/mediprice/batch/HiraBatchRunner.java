package com.mediprice.batch;

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
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

// 배치 실행기
//   새로 실행        : bootRun
//   실패한 곳부터 이어서: bootRun --args='--restart'
//   다른 Job 실행     : bootRun --args='--job=hiraCodeJob'
@Component
public class HiraBatchRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(HiraBatchRunner.class);

    private static final String DEFAULT_JOB = "hiraSyncJob";
    // 실행 기준 시각. 이어서 실행할 때도 처음 값 그대로 씀 (정리 단계의 기준)
    public static final String REQUESTED_AT = "requestedAt";

    // 프로그램이 갑자기 꺼져서 "실행 중"으로 남은 상태
    private static final Set<BatchStatus> STALE = Set.of(BatchStatus.STARTED, BatchStatus.STARTING,
            BatchStatus.STOPPING, BatchStatus.UNKNOWN);

    private final JobOperator jobOperator;
    private final JobRepository jobRepository;
    private final Map<String, Job> jobs; // Job 빈 이름 → Job

    private int exitCode = 0;

    public HiraBatchRunner(JobOperator jobOperator, JobRepository jobRepository, Map<String, Job> jobs) {
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

        JobParameters params = args.containsOption("restart")
                ? lastFailedParameters(jobName)
                : new JobParametersBuilder().addLocalDateTime(REQUESTED_AT, LocalDateTime.now()).toJobParameters();

        // 같은 파라미터의 실패한 실행이 있으면 Spring Batch가 실패한 Step부터 이어서 실행함
        JobExecution execution = jobOperator.start(job, params);
        log.info("{} 종료: {} (파라미터 {})", jobName, execution.getStatus(), params);

        // 실패하면 종료 코드 1 (나중에 자동 실행 도구가 실패를 알 수 있게)
        if (execution.getStatus() != BatchStatus.COMPLETED) {
            exitCode = 1;
        }
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

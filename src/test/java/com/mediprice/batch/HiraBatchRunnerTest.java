package com.mediprice.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.TimeZone;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;

import com.mediprice.batch.job.PriceHistoryTasklet;
import com.mediprice.batch.job.StaleRowCleanupTasklet;

@SpringBootTest(classes = HiraBatchRunnerTest.TestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.batch.job.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class HiraBatchRunnerTest {

    static int runs;

    @Autowired JobOperator jobOperator;
    @Autowired JobRepository jobRepository;
    @Autowired JdbcTemplate jdbc;
    @Autowired Job testJob;

    HiraBatchRunner runner;

    @BeforeEach
    void setUp() {
        runs = 0;
        runner = new HiraBatchRunner(jobOperator, jobRepository, Map.of("testJob", testJob), KoreaTime.clock());
    }

    JobExecution leftStarted() {
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime(HiraBatchRunner.REQUESTED_AT, LocalDateTime.of(2026, 1, 5, 3, 0)).toJobParameters();
        JobInstance instance = jobRepository.createJobInstance("testJob", params);
        JobExecution execution = jobRepository.createJobExecution(instance, params, new ExecutionContext());
        execution.setStartTime(LocalDateTime.now());
        execution.setStatus(BatchStatus.STARTED);
        jobRepository.update(execution);
        return execution;
    }

    void age(String table, LocalDateTime when) {
        jdbc.update("UPDATE " + table + " SET LAST_UPDATED = ?", when);
    }

    String status(JobExecution execution) {
        return jdbc.queryForObject("SELECT STATUS FROM BATCH_JOB_EXECUTION WHERE JOB_EXECUTION_ID = ?",
                String.class, execution.getId());
    }

    void run(String... args) throws Exception {
        runner.run(new DefaultApplicationArguments(args));
    }

    @Test
    void 최근_기록이_있는_실행이_있으면_새_실행을_시작하지_않음() throws Exception {
        JobExecution running = leftStarted();

        run("--job=testJob");

        assertThat(runs).isZero();
        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(status(running)).isEqualTo("STARTED");
    }

    @Test
    void 최근_기록이_있는_실행이_있으면_restart도_실패로_정리하지_않음() throws Exception {
        JobExecution running = leftStarted();

        run("--job=testJob", "--restart");

        assertThat(runs).isZero();
        assertThat(runner.getExitCode()).isEqualTo(1);
        assertThat(status(running)).isEqualTo("STARTED");
    }

    @Test
    void Job_기록이_오래돼도_Step_기록이_최근이면_실행_중으로_봄() throws Exception {
        JobExecution running = leftStarted();
        StepExecution step = jobRepository.createStepExecution("testStep", running);
        step.setStartTime(LocalDateTime.now());
        jobRepository.update(step);
        age("BATCH_JOB_EXECUTION", LocalDateTime.now().minusHours(2));

        run("--job=testJob");

        assertThat(runs).isZero();
        assertThat(runner.getExitCode()).isEqualTo(1);
    }

    @Test
    void 기록이_오래된_실행은_꺼진_것으로_보고_새_실행을_시작함() throws Exception {
        leftStarted();
        age("BATCH_JOB_EXECUTION", LocalDateTime.now().minusHours(2));

        run("--job=testJob");

        assertThat(runs).isEqualTo(1);
        assertThat(runner.getExitCode()).isZero();
    }

    @Test
    void 기록이_오래된_실행은_restart로_실패_정리_후_이어서_실행함() throws Exception {
        JobExecution crashed = leftStarted();
        age("BATCH_JOB_EXECUTION", LocalDateTime.now().minusHours(2));

        run("--job=testJob", "--restart");

        assertThat(runs).isEqualTo(1);
        assertThat(runner.getExitCode()).isZero();
        assertThat(status(crashed)).isEqualTo("FAILED");
    }

    @Test
    void JVM_기본_시간대가_UTC여도_requestedAt은_Asia_Seoul_시각임() throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        Instant instant = Instant.parse("2026-01-05T15:30:00Z");
        runner = new HiraBatchRunner(jobOperator, jobRepository, Map.of("testJob", testJob),
                Clock.fixed(instant, KoreaTime.ZONE));

        run("--job=testJob");

        JobParameters saved = jobRepository.getLastJobExecution(jobRepository.getLastJobInstance("testJob"))
                .getJobParameters();
        assertThat(saved.getLocalDateTime(HiraBatchRunner.REQUESTED_AT))
                .isEqualTo(LocalDateTime.of(2026, 1, 6, 0, 30));
    }

    @Test
    void 기본_생성자의_requestedAt도_Asia_Seoul_벽시계_시각임() throws Exception {
        assertThat(TimeZone.getDefault().getID()).isEqualTo("UTC");
        LocalDateTime before = LocalDateTime.now(KoreaTime.ZONE);

        run("--job=testJob");

        JobParameters saved = jobRepository.getLastJobExecution(jobRepository.getLastJobInstance("testJob"))
                .getJobParameters();
        assertThat(saved.getLocalDateTime(HiraBatchRunner.REQUESTED_AT))
                .isBetween(before, LocalDateTime.now(KoreaTime.ZONE));
    }

    @Test
    void requestedAt이_없으면_두_Tasklet_모두_실패함() {
        ChunkContext context = mock(ChunkContext.class, RETURNS_DEEP_STUBS);
        when(context.getStepContext().getStepExecution().getJobParameters()).thenReturn(new JobParameters());
        NamedParameterJdbcTemplate template = mock(NamedParameterJdbcTemplate.class);

        assertThatThrownBy(() -> new PriceHistoryTasklet(template).execute(mock(StepContribution.class), context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requestedAt");
        assertThatThrownBy(() -> new StaleRowCleanupTasklet(template, jobRepository, List.of("t"), Map.of(), Map.of())
                .execute(mock(StepContribution.class), context))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("requestedAt");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApp {

        @Bean Job testJob(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
            return new JobBuilder("testJob", jobRepository)
                    .start(new StepBuilder("testStep", jobRepository)
                            .tasklet((contribution, chunkContext) -> {
                                runs++;
                                return RepeatStatus.FINISHED;
                            }, transactionManager)
                            .build())
                    .build();
        }
    }
}

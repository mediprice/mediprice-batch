package com.mediprice.batch.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.mediprice.batch.HiraBatchRunner;

@SpringBootTest(classes = PricedHospitalReaderTest.TestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.batch.job.enabled=false"
})
class PricedHospitalReaderTest {

    static final List<String> written = new ArrayList<>();
    static boolean fail;

    @Autowired PricedHospitalReader reader;
    @Autowired JdbcTemplate jdbc;
    @Autowired JobOperator jobOperator;
    @Autowired Job detailJob;

    @Test
    void Step_범위_빈이어도_재시작하면_저장된_위치부터_읽음() throws Exception {
        assertThat(AopUtils.isCglibProxy(reader)).isTrue();
        jdbc.execute("CREATE TABLE hospital_npay_price (ykiho varchar)");
        for (String ykiho : List.of("a", "b", "c", "d", "e")) {
            jdbc.update("INSERT INTO hospital_npay_price VALUES (?)", ykiho);
        }
        JobParameters params = new JobParametersBuilder()
                .addLocalDateTime(HiraBatchRunner.REQUESTED_AT, LocalDateTime.of(2026, 1, 5, 3, 0)).toJobParameters();

        fail = true;
        assertThat(jobOperator.start(detailJob, params).getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(written).containsExactly("a", "b");

        fail = false;
        assertThat(jobOperator.start(detailJob, params).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(written).containsExactly("a", "b", "c", "d", "e");
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(PricedHospitalReader.class)
    static class TestApp {

        @Bean Job detailJob(JobRepository jobRepository, PlatformTransactionManager transactionManager, PricedHospitalReader reader) {
            return new JobBuilder("detailJob", jobRepository)
                    .start(new StepBuilder("detailStep", jobRepository)
                            .<String, String>chunk(2)
                            .reader(reader)
                            .writer(chunk -> {
                                if (fail && !written.isEmpty()) {
                                    throw new IllegalStateException("simulated");
                                }
                                written.addAll(chunk.getItems());
                            })
                            .transactionManager(transactionManager)
                            .build())
                    .build();
        }
    }
}

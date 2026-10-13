package com.mediprice.batch.job;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

// HIRA 동기화 Job·Step 등록
@Configuration

public class HiraSyncJobConfig {

    @Bean
    public Job hiraSyncJob(JobRepository jobRepository, Step sidoStep, Step npayItemStep) {
        return new JobBuilder("hiraSyncJob", jobRepository)
                // 개발 중 반복 실행용: 실행마다 run.id를 올려 새 실행으로 만듦
                .incrementer(new RunIdIncrementer())
                .start(sidoStep)
                .next(npayItemStep)
                .build();
    }

    @Bean
    public Step sidoStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         SidoCodeTasklet sidoCodeTasklet) {
        return new StepBuilder("sidoStep", jobRepository)
                .tasklet(sidoCodeTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step npayItemStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             NpayItemTasklet npayItemTasklet) {
        return new StepBuilder("npayItemStep", jobRepository)
                .tasklet(npayItemTasklet, transactionManager)
                .build();
    }
}

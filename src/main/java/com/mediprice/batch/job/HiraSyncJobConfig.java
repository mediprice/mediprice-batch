package com.mediprice.batch.job;

import java.util.Map;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.job.parameters.RunIdIncrementer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import com.mediprice.batch.hira.HiraClient;
import com.mediprice.batch.hira.HiraPagingItemReader;

// HIRA 동기화 Job·Step 등록
@Configuration

public class HiraSyncJobConfig {

    // HIRA 한 페이지 건수 = DB에 한 번에 저장하는 건수 (HIRA 최대 1000)
    private static final int PAGE_SIZE = 1000;

    @Bean
    public Job hiraSyncJob(JobRepository jobRepository, Step sidoStep, Step npayItemStep,
                           Step hospitalStep, Step npayPriceStep) {
        return new JobBuilder("hiraSyncJob", jobRepository)
                // 개발 중 반복 실행용: 실행마다 run.id를 올려 새 실행으로 만듦
                .incrementer(new RunIdIncrementer())
                .start(sidoStep)
                .next(npayItemStep)
                .next(hospitalStep)
                .next(npayPriceStep)
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

    // 병원 약 8만 건: 1000건씩 읽고 → 1000건씩 저장을 반복
    @Bean
    public Step hospitalStep(JobRepository jobRepository,
                             PlatformTransactionManager transactionManager,
                             HiraClient hiraClient,
                             HospitalWriter hospitalWriter) {
        return new StepBuilder("hospitalStep", jobRepository)
                .<Map<String, String>, Map<String, String>>chunk(PAGE_SIZE)
                .reader(new HiraPagingItemReader(hiraClient,
                        "/hospInfoServicev2/getHospBasisList", Map.of(), PAGE_SIZE))
                .writer(hospitalWriter)
                .transactionManager(transactionManager)
                .build();
    }

    // 병원별 가격 약 28만 건: ykiho 없이 호출하면 전체 병원 가격이 옴. hospital, npay_item이 먼저 저장돼 있어야 함 (FK)
    @Bean
    public Step npayPriceStep(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              HiraClient hiraClient,
                              NpayPriceWriter npayPriceWriter) {
        return new StepBuilder("npayPriceStep", jobRepository)
                .<Map<String, String>, Map<String, String>>chunk(PAGE_SIZE)
                .reader(new HiraPagingItemReader(hiraClient,
                        "/nonPaymentDamtInfoService/getNonPaymentItemHospDtlList", Map.of(), PAGE_SIZE))
                .writer(npayPriceWriter)
                .transactionManager(transactionManager)
                .build();
    }
}

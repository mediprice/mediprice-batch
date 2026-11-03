package com.mediprice.batch.job;

import java.util.List;
import java.util.Map;

import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.listener.ExecutionContextPromotionListener;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.mediprice.batch.hira.HiraClient;
import com.mediprice.batch.hira.HiraPagingItemReader;

// HIRA 동기화 Job·Step 등록
// 실행 파라미터(requestedAt)는 HiraBatchRunner가 넣음. 실패하면 같은 파라미터로 다시 실행해 실패한 Step부터 이어감
@Configuration

public class HiraSyncJobConfig {

    // HIRA 한 페이지 건수 = DB에 한 번에 저장하는 건수 (HIRA 최대 1000)
    private static final int PAGE_SIZE = 1000;
    // 병원 상세는 병원마다 1번씩 호출해서 100곳마다 저장
    private static final int DETAIL_CHUNK_SIZE = 100;
    // 페이지로 읽는 테이블의 전체 건수 저장 키
    private static final String HOSPITAL_TOTAL_KEY = StaleRowCleanupTasklet.totalCountKey("hospital");
    private static final String PRICE_TOTAL_KEY = StaleRowCleanupTasklet.totalCountKey("hospital_npay_price");

    // 전체 동기화: 코드 → 항목 → 병원 → 가격 → 정리 → 가격 이력 → 병원 상세 → 상세 정리
    @Bean
    public Job hiraSyncJob(JobRepository jobRepository,
                           Step sidoStep, Step sgguStep, Step institutionClassStep,
                           Step npayItemStep, Step hospitalStep, Step npayPriceStep,
                           Step syncCleanupStep, Step priceHistoryStep,
                           Step hospitalDetailStep, Step detailCleanupStep) {
        return new JobBuilder("hiraSyncJob", jobRepository)
                .start(sidoStep)
                .next(sgguStep)
                .next(institutionClassStep)
                .next(npayItemStep)
                .next(hospitalStep)
                .next(npayPriceStep)
                .next(syncCleanupStep)
                .next(priceHistoryStep)
                .next(hospitalDetailStep)
                .next(detailCleanupStep)
                .build();
    }

    // 코드 테이블만 따로 돌리는 Job (시도 → 시군구 → 종별)
    @Bean
    public Job hiraCodeJob(JobRepository jobRepository,
                           Step sidoStep, Step sgguStep, Step institutionClassStep) {
        return new JobBuilder("hiraCodeJob", jobRepository)
                .start(sidoStep)
                .next(sgguStep)
                .next(institutionClassStep)
                .build();
    }

    // 가격 이력 복사만 따로 돌리는 Job
    @Bean
    public Job priceHistoryJob(JobRepository jobRepository, Step priceHistoryStep) {
        return new JobBuilder("priceHistoryJob", jobRepository)
                .start(priceHistoryStep)
                .build();
    }

    // 병원 상세만 따로 돌리는 Job. 상세정보 API 하루 한도(1만 건) 때문에 하루 2번까지만 실행 가능
    @Bean
    public Job hospitalDetailJob(JobRepository jobRepository, Step hospitalDetailStep) {
        return new JobBuilder("hospitalDetailJob", jobRepository)
                .start(hospitalDetailStep)
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
    public Step sgguStep(JobRepository jobRepository,
                         PlatformTransactionManager transactionManager,
                         SgguCodeTasklet sgguCodeTasklet) {
        return new StepBuilder("sgguStep", jobRepository)
                .tasklet(sgguCodeTasklet, transactionManager)
                .build();
    }

    @Bean
    public Step institutionClassStep(JobRepository jobRepository,
                                     PlatformTransactionManager transactionManager,
                                     InstitutionClassTasklet institutionClassTasklet) {
        return new StepBuilder("institutionClassStep", jobRepository)
                .tasklet(institutionClassTasklet, transactionManager)
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
                        "/hospInfoServicev2/getHospBasisList", Map.of(), PAGE_SIZE, HOSPITAL_TOTAL_KEY))
                .writer(hospitalWriter)
                .listener(promoteToJob(HOSPITAL_TOTAL_KEY))
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
                        "/nonPaymentDamtInfoService/getNonPaymentItemHospDtlList", Map.of(), PAGE_SIZE, PRICE_TOTAL_KEY))
                .writer(npayPriceWriter)
                .listener(promoteToJob(PRICE_TOTAL_KEY))
                .transactionManager(transactionManager)
                .build();
    }

    // HIRA에서 사라진 행 삭제. 참조하는 테이블부터 지움 (병원이 지워지면 그 병원 상세도 DB가 같이 지움)
    @Bean
    public Step syncCleanupStep(JobRepository jobRepository,
                                PlatformTransactionManager transactionManager,
                                NamedParameterJdbcTemplate jdbcTemplate) {
        List<String> tables = List.of(
                "hospital_npay_price", "hospital", "npay_item",
                "region_sggu", "region_sido", "institution_class");
        return new StepBuilder("syncCleanupStep", jobRepository)
                .tasklet(new StaleRowCleanupTasklet(jdbcTemplate, jobRepository, tables), transactionManager)
                .build();
    }

    // 정리가 끝난 현재 가격 전체를 이번 달 기록으로 복사
    @Bean
    public Step priceHistoryStep(JobRepository jobRepository,
                                 PlatformTransactionManager transactionManager,
                                 PriceHistoryTasklet priceHistoryTasklet) {
        return new StepBuilder("priceHistoryStep", jobRepository)
                .tasklet(priceHistoryTasklet, transactionManager)
                .build();
    }

    // 가격 데이터가 있는 병원(약 4천 곳)마다 세부정보 호출 → hospital_detail
    @Bean
    public Step hospitalDetailStep(JobRepository jobRepository,
                                   PlatformTransactionManager transactionManager,
                                   PricedHospitalReader pricedHospitalReader,
                                   HospitalDetailProcessor hospitalDetailProcessor,
                                   HospitalDetailWriter hospitalDetailWriter) {
        return new StepBuilder("hospitalDetailStep", jobRepository)
                .<String, HospitalDetailProcessor.DetailRow>chunk(DETAIL_CHUNK_SIZE)
                .reader(pricedHospitalReader)
                .processor(hospitalDetailProcessor)
                .writer(hospitalDetailWriter)
                .transactionManager(transactionManager)
                .build();
    }

    // 가격 데이터가 없어진 병원의 상세 삭제
    @Bean
    public Step detailCleanupStep(JobRepository jobRepository,
                                  PlatformTransactionManager transactionManager,
                                  NamedParameterJdbcTemplate jdbcTemplate) {
        return new StepBuilder("detailCleanupStep", jobRepository)
                .tasklet(new StaleRowCleanupTasklet(jdbcTemplate, jobRepository, List.of("hospital_detail")), transactionManager)
                .build();
    }

    // Reader가 Step 실행 기록에 남긴 값을 Job 실행 기록으로 올림. 이어서 실행해 완료된 Step을 건너뛰어도 정리 Step이 읽을 수 있음
    private ExecutionContextPromotionListener promoteToJob(String key) {
        ExecutionContextPromotionListener listener = new ExecutionContextPromotionListener();
        listener.setKeys(new String[] {key});
        return listener;
    }
}

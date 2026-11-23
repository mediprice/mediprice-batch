package com.mediprice.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.mediprice.batch.HiraBatchRunner;
import com.mediprice.batch.KoreaTime;
import com.mediprice.batch.job.CleanupFlowTest.FakeHira;

/** 실제 Writer·정리·이력 SQL을 db/mediprice.sql로 만든 PostGIS에서 돌려 "바뀐 행만 쓰기"와 "사라진 행만 지우기"를 확인한다. */
@Testcontainers(disabledWithoutDocker = true)
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = PostgresSyncFlowTest.TestApp.class, properties = {
        "spring.batch.jdbc.initialize-schema=always",
        "spring.batch.job.enabled=false"
})
class PostgresSyncFlowTest {

    static final String HOSP = "/hospInfoServicev2/getHospBasisList";
    static final String PRICE = "/nonPaymentDamtInfoService/getNonPaymentItemHospDtlList";
    static final String ITEM = "/nonPaymentDamtInfoService/getNonPaymentItemCodeList2";
    static final int HOSPITALS = 1200;
    static final LocalDateTime W1 = LocalDateTime.of(2026, 1, 5, 3, 0);

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres"));

    static boolean schemaLoaded;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired JobOperator jobOperator;
    @Autowired Job hiraSyncJob;
    @Autowired FakeHira hira;

    @BeforeEach
    void setUp() {
        if (!schemaLoaded) {
            new ResourceDatabasePopulator(new FileSystemResource("db/mediprice.sql")).execute(jdbc.getDataSource());
            schemaLoaded = true;
        }
        String tables = jdbc.queryForObject("SELECT string_agg(quote_ident(tablename), ', ') FROM pg_tables "
                + "WHERE schemaname = 'public' AND tablename <> 'spatial_ref_sys'", String.class);
        jdbc.execute("TRUNCATE " + tables + " CASCADE");

        hira.rows.clear();
        hira.before.clear();
        hira.calls.clear();
        hira.rows.put(ITEM, new ArrayList<>(List.of(item("MX1"), item("MX2"))));
        List<Map<String, String>> hospitals = new ArrayList<>();
        List<Map<String, String>> prices = new ArrayList<>();
        for (int i = 0; i < HOSPITALS; i++) {
            String ykiho = ykiho(i);
            hospitals.add(hospital(ykiho, "02-000-" + i));
            prices.add(price(ykiho, 1, "MX1", 10000 + i));
            prices.add(price(ykiho, 2, "MX2", 20000 + i));
        }
        hira.rows.put(HOSP, hospitals);
        hira.rows.put(PRICE, prices);
    }

    static String ykiho(int i) {
        return "JDQ4MTg4MSM1MSMkMSMkMCMkODkkMzgxMzUxIzExIyQxIyQzIyQ3OSQyNjEwMDIjNjEjJDEjJDgjJDgz%04d".formatted(i);
    }

    static Map<String, String> item(String code) {
        return Map.of("npayCd", code, "npayKorNm", "항목 " + code, "npayMdivCd", "1070Z", "npayMdivCdNm", "이학요법료",
                "adtFrDd", "20240101", "adtEndDd", "99991231");
    }

    static Map<String, String> hospital(String ykiho, String telno) {
        Map<String, String> m = new HashMap<>(Map.of("ykiho", ykiho, "yadmNm", "병원" + ykiho.substring(ykiho.length() - 4),
                "clCd", "31", "clCdNm", "의원", "sidoCd", "110000", "sidoCdNm", "서울", "sgguCd", "110016",
                "sgguCdNm", "종로구", "telno", telno, "estbDd", "19790324"));
        m.putAll(Map.of("drTotCnt", "3", "XPos", "126.9784", "YPos", "37.5666", "mdeptGdrCnt", "1"));
        return m;
    }

    static Map<String, String> price(String ykiho, int sno, String npayCd, long amt) {
        return Map.of("ykiho", ykiho, "sno", String.valueOf(sno), "npayCd", npayCd, "yadmNpayCdNm", "자체명 " + npayCd,
                "curAmt", String.valueOf(amt), "adtFrDd", "20240101", "adtEndDd", "99991231",
                "urlAddr", "https://example.invalid/" + sno);
    }

    JobExecution run(LocalDateTime requestedAt) throws Exception {
        return jobOperator.start(hiraSyncJob, new JobParametersBuilder()
                .addLocalDateTime(HiraBatchRunner.REQUESTED_AT, requestedAt).toJobParameters());
    }

    /** 행 키 → xmin. 행을 다시 쓰면 xmin이 바뀐다 */
    Map<String, String> versions(String table, String key) {
        Map<String, String> out = new HashMap<>();
        jdbc.query("SELECT " + key + " AS k, xmin::text AS x FROM " + table,
                rs -> { out.put(rs.getString("k"), rs.getString("x")); });
        return out;
    }

    static List<String> rewritten(Map<String, String> before, Map<String, String> after) {
        List<String> keys = new ArrayList<>();
        before.forEach((k, x) -> {
            if (after.containsKey(k) && !after.get(k).equals(x)) {
                keys.add(k);
            }
        });
        return keys;
    }

    int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void 같은_데이터로_다시_돌리면_병원_가격_이력을_다시_쓰지_않고_바뀐_행만_씀() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, String> hosp1 = versions("hospital", "ykiho");
        Map<String, String> price1 = versions("hospital_npay_price", "ykiho || '/' || sno");
        Map<String, String> hist1 = versions("npay_price_history", "ykiho || '/' || sno");
        assertThat(price1).hasSize(HOSPITALS * 2);
        assertThat(hist1).hasSize(HOSPITALS * 2);

        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rewritten(hosp1, versions("hospital", "ykiho"))).as("병원 다시 쓴 행").isEmpty();
        assertThat(rewritten(price1, versions("hospital_npay_price", "ykiho || '/' || sno"))).as("가격 다시 쓴 행").isEmpty();
        assertThat(rewritten(hist1, versions("npay_price_history", "ykiho || '/' || sno"))).as("이력 다시 쓴 행").isEmpty();

        // 가격 하나와 병원 전화번호 하나만 바꾸면 그 행만 새로 씀 (같은 달 이력도 그 행만)
        hira.rows.get(PRICE).set(0, price(ykiho(0), 1, "MX1", 99999));
        hira.rows.get(HOSP).set(7, hospital(ykiho(7), "02-999-9999"));
        assertThat(run(W1.plusWeeks(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rewritten(hosp1, versions("hospital", "ykiho"))).containsExactly(ykiho(7));
        assertThat(rewritten(price1, versions("hospital_npay_price", "ykiho || '/' || sno"))).containsExactly(ykiho(0) + "/1");
        assertThat(rewritten(hist1, versions("npay_price_history", "ykiho || '/' || sno"))).containsExactly(ykiho(0) + "/1");
        assertThat(count("SELECT cur_amt FROM npay_price_history WHERE ykiho = '" + ykiho(0) + "' AND sno = 1")).isEqualTo(99999);
    }

    @Test
    void 두_번_연속_안_보인_행만_지우고_다시_보인_행은_남기며_나머지_행은_건드리지_않음() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, String> price1 = versions("hospital_npay_price", "ykiho || '/' || sno");
        Map<String, String> hosp1 = versions("hospital", "ykiho");

        // 가격 한 줄, 병원 한 곳(가격 포함), 다음 실행에 다시 나타날 가격 한 줄이 HIRA 목록에서 빠짐
        List<Map<String, String>> prices = hira.rows.get(PRICE);
        Map<String, String> comesBack = prices.stream()
                .filter(m -> m.get("ykiho").equals(ykiho(9)) && m.get("sno").equals("1")).findFirst().orElseThrow();
        prices.removeIf(m -> (m.get("ykiho").equals(ykiho(3)) && m.get("sno").equals("2"))
                || m.get("ykiho").equals(ykiho(5)) || m == comesBack);
        hira.rows.get(HOSP).removeIf(m -> m.get("ykiho").equals(ykiho(5)));

        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM hospital_npay_price")).as("한 번 안 보인 행은 유지").isEqualTo(HOSPITALS * 2);
        assertThat(jdbc.queryForList("SELECT ykiho || '/' || sno FROM hospital_npay_price WHERE missed_since IS NOT NULL ORDER BY 1",
                String.class)).containsExactly(ykiho(3) + "/2", ykiho(5) + "/1", ykiho(5) + "/2", ykiho(9) + "/1");
        assertThat(jdbc.queryForList("SELECT ykiho FROM hospital WHERE missed_since IS NOT NULL", String.class))
                .containsExactly(ykiho(5));
        assertThat(rewritten(price1, versions("hospital_npay_price", "ykiho || '/' || sno")))
                .as("표시한 행만 다시 씀").hasSize(4);
        assertThat(rewritten(hosp1, versions("hospital", "ykiho"))).containsExactly(ykiho(5));
        assertThat(count("SELECT count(*) FROM hospital_seen") + count("SELECT count(*) FROM hospital_npay_price_seen"))
                .as("정리 Step이 본 키를 비움").isZero();

        prices.add(comesBack);
        assertThat(run(W1.plusWeeks(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM hospital_npay_price")).isEqualTo(HOSPITALS * 2 - 3);
        assertThat(count("SELECT count(*) FROM hospital_npay_price WHERE ykiho IN ('" + ykiho(3) + "', '" + ykiho(5) + "') AND sno = 2"))
                .isZero();
        assertThat(count("SELECT count(*) FROM hospital")).isEqualTo(HOSPITALS - 1);
        assertThat(count("SELECT count(*) FROM hospital WHERE ykiho = '" + ykiho(5) + "'")).isZero();
        assertThat(count("SELECT count(*) FROM hospital_npay_price WHERE missed_since IS NOT NULL")).as("다시 보인 행은 표시 해제").isZero();
        assertThat(count("SELECT count(*) FROM npay_price_history")).as("이번 달 이력도 현재 가격에 맞춤")
                .isEqualTo(HOSPITALS * 2 - 3);

        // 마지막 VACUUM Step이 트랜잭션 밖에서 실제로 돌았음. 통계 반영이 늦을 수 있어 잠깐 기다림
        assertThat(vacuumedTables()).isEqualTo(4);
    }

    int vacuumedTables() throws InterruptedException {
        String sql = "SELECT count(*) FROM pg_stat_user_tables WHERE relname IN "
                + "('hospital_npay_price', 'hospital', 'npay_price_history', 'hospital_detail') "
                + "AND last_vacuum IS NOT NULL AND last_analyze IS NOT NULL";
        int vacuumed = 0;
        for (int i = 0; i < 50 && vacuumed < 4; i++) {
            if (i > 0) {
                Thread.sleep(100);
            }
            vacuumed = count(sql);
        }
        return vacuumed;
    }

    @Test
    void 빈_값이_채워지거나_값이_비워져도_바뀐_행으로_보고_씀() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Map<String, String> hosp1 = versions("hospital", "ykiho");
        Map<String, String> price1 = versions("hospital_npay_price", "ykiho || '/' || sno");

        // 병원 11: 홈페이지 NULL → 값, 가격 (12, 1): 적용 종료일 값 → 빈 값(NULL)
        Map<String, String> withUrl = new HashMap<>(hospital(ykiho(11), "02-000-11"));
        withUrl.put("hospUrl", "https://example.invalid");
        hira.rows.get(HOSP).set(11, withUrl);
        Map<String, String> noEnd = new HashMap<>(price(ykiho(12), 1, "MX1", 10012));
        noEnd.put("adtEndDd", "");
        hira.rows.get(PRICE).set(24, noEnd);

        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rewritten(hosp1, versions("hospital", "ykiho"))).containsExactly(ykiho(11));
        assertThat(rewritten(price1, versions("hospital_npay_price", "ykiho || '/' || sno"))).containsExactly(ykiho(12) + "/1");
        assertThat(jdbc.queryForObject("SELECT hosp_url FROM hospital WHERE ykiho = ?", String.class, ykiho(11)))
                .isEqualTo("https://example.invalid");
        assertThat(count("SELECT count(*) FROM hospital_npay_price WHERE ykiho = '" + ykiho(12) + "' AND sno = 1 AND adt_end_dd IS NULL"))
                .isEqualTo(1);
    }

    @Test
    void VACUUM_권한이_없거나_잠금으로_건너뛰면_완료가_아니라_경고를_남김(CapturedOutput output) throws Exception {
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'vacuum_other') THEN "
                + "CREATE ROLE vacuum_other LOGIN PASSWORD 'pw'; END IF; END $$");
        DriverManagerDataSource other = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), "vacuum_other", "pw");
        new VacuumTasklet(other, List.of("hospital")).execute(mock(StepContribution.class), mock(ChunkContext.class));
        assertThat(output.getOut()).contains("hospital: VACUUM 권한이 없어 건너뜀")
                .doesNotContain("hospital: VACUUM (ANALYZE) 완료");

        // 다른 세션이 VACUUM과 겹치는 잠금을 잡고 있으면 SKIP_LOCKED가 WARNING만 내고 건너뜀 → 경고 로그
        try (Connection locker = jdbc.getDataSource().getConnection()) {
            locker.setAutoCommit(false);
            locker.createStatement().execute("LOCK TABLE hospital_detail IN SHARE UPDATE EXCLUSIVE MODE");
            new VacuumTasklet(jdbc.getDataSource(), List.of("hospital_detail"))
                    .execute(mock(StepContribution.class), mock(ChunkContext.class));
            locker.rollback();
        }
        assertThat(output.getOut()).contains("hospital_detail: VACUUM (ANALYZE) 실패, 배치는 성공으로 둠")
                .contains("lock not available")
                .doesNotContain("hospital_detail: VACUUM (ANALYZE) 완료");
    }

    @Test
    void 통계와_사이트맵_lastmod를_백엔드_계산식과_같게_채우고_다시_돌려도_바뀐_행만_씀() throws Exception {
        // 가격 없는 병원 하나(통계의 병원 수에서 빠져야 함)와 가격 없는 항목 MX3(항목 수에서 빠져야 함)
        hira.rows.get(HOSP).add(hospital(ykiho(HOSPITALS), "02-111-1111"));
        hira.rows.get(ITEM).add(item("MX3"));
        // 병원 0: 시작일 여러 개 + 미래 하나, 병원 1: 시작일 모두 비어 있음
        String today = LocalDate.now(KoreaTime.ZONE).toString().replace("-", "");
        List<Map<String, String>> prices = hira.rows.get(PRICE);
        prices.set(0, withAdtFrDd(price(ykiho(0), 1, "MX1", 10000), "20250310"));
        prices.set(1, withAdtFrDd(price(ykiho(0), 2, "MX2", 20000), "20991231"));
        prices.set(2, withAdtFrDd(price(ykiho(1), 1, "MX1", 10001), ""));
        prices.set(3, withAdtFrDd(price(ykiho(1), 2, "MX2", 20001), ""));
        prices.set(4, withAdtFrDd(price(ykiho(2), 1, "MX1", 10002), today));

        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        // 병원 상세 Step은 이 테스트에서 가짜라 상세 행을 직접 넣고 lastmod Step만 다시 돌린다 (hospitalDetailJob과 같은 순서)
        jdbc.update("INSERT INTO hospital_detail (ykiho) SELECT ykiho FROM hospital WHERE ykiho IN (?, ?, ?, ?)",
                ykiho(0), ykiho(1), ykiho(2), ykiho(3));
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 백엔드 PriceStatsQuery가 세던 식: count(distinct ykiho), count(distinct npay_cd), count(*)
        Map<String, Object> stats = jdbc.queryForMap("SELECT hospital_count, npay_item_count, price_count, data_date FROM data_stats");
        assertThat(stats.get("hospital_count")).isEqualTo(count("SELECT count(DISTINCT ykiho) FROM hospital_npay_price"))
                .isEqualTo(HOSPITALS);
        assertThat(count("SELECT count(*) FROM hospital")).as("전체 병원 수와 다름").isEqualTo(HOSPITALS + 1);
        assertThat(stats.get("npay_item_count")).isEqualTo(count("SELECT count(DISTINCT npay_cd) FROM hospital_npay_price"))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM npay_item")).as("전체 항목 수와 다름").isEqualTo(3);
        assertThat(stats.get("price_count")).isEqualTo(HOSPITALS * 2);
        assertThat(stats.get("data_date").toString()).isEqualTo(LocalDate.now(KoreaTime.ZONE).toString());
        assertThat(count("SELECT count(*) FROM data_stats")).isEqualTo(1);

        // 백엔드 사이트맵 식: max(adt_fr_dd) FILTER (adt_fr_dd <= 오늘)
        assertThat(lastmod(ykiho(0))).as("미래 시작일은 뺌").isEqualTo("2025-03-10");
        assertThat(lastmod(ykiho(1))).as("시작일이 모두 비면 null").isNull();
        assertThat(lastmod(ykiho(2))).as("오늘 시작은 포함").isEqualTo(LocalDate.now(KoreaTime.ZONE).toString());
        assertThat(lastmod(ykiho(3))).isEqualTo("2024-01-01");

        // 값이 같으면 상세 행도 통계 행도 값이 같고, 상세 행은 다시 쓰지 않음
        Map<String, String> detail = versions("hospital_detail", "ykiho");
        assertThat(run(W1.plusWeeks(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(rewritten(detail, versions("hospital_detail", "ykiho"))).isEmpty();

        // 가격이 하나도 없는 병원이 되면 null, 가격이 0건이면 data_date도 null
        jdbc.update("DELETE FROM hospital_npay_price WHERE ykiho = ?", ykiho(3));
        new PriceLastmodTasklet(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), KoreaTime.clock())
                .execute(null, null);
        assertThat(lastmod(ykiho(3))).as("가격이 없어지면 null").isNull();
        jdbc.update("DELETE FROM hospital_npay_price");
        new DataStatsTasklet(new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc), KoreaTime.clock())
                .execute(null, null);
        assertThat(jdbc.queryForMap("SELECT hospital_count, npay_item_count, price_count, data_date FROM data_stats").values())
                .containsExactly(0, 0, 0, null);
    }

    static Map<String, String> withAdtFrDd(Map<String, String> price, String adtFrDd) {
        Map<String, String> m = new HashMap<>(price);
        m.put("adtFrDd", adtFrDd);
        return m;
    }

    String lastmod(String ykiho) {
        return jdbc.queryForObject("SELECT price_lastmod::text FROM hospital_detail WHERE ykiho = ?", String.class, ykiho);
    }

    // 실제 Writer·항목·이력 Tasklet을 쓰고, HIRA는 가짜. 코드 테이블과 병원 상세는 이 테스트 범위 밖이라 빈 동작
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import({HiraSyncJobConfig.class, HospitalWriter.class, NpayPriceWriter.class, NpayItemTasklet.class,
            PriceHistoryTasklet.class, PriceLastmodTasklet.class, DataStatsTasklet.class})
    static class TestApp {

        static <T extends Tasklet> T finished(Class<T> type) throws Exception {
            T tasklet = mock(type);
            when(tasklet.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
            return tasklet;
        }

        @Bean FakeHira hiraClient() {
            return new FakeHira();
        }

        @Bean SidoCodeTasklet sidoCodeTasklet() throws Exception {
            return finished(SidoCodeTasklet.class);
        }

        @Bean SgguCodeTasklet sgguCodeTasklet() throws Exception {
            return finished(SgguCodeTasklet.class);
        }

        @Bean InstitutionClassTasklet institutionClassTasklet() throws Exception {
            return finished(InstitutionClassTasklet.class);
        }

        @Bean PricedHospitalReader pricedHospitalReader() {
            return mock(PricedHospitalReader.class);
        }

        @Bean HospitalDetailProcessor hospitalDetailProcessor() {
            return mock(HospitalDetailProcessor.class);
        }

        @Bean HospitalDetailWriter hospitalDetailWriter() {
            return mock(HospitalDetailWriter.class);
        }
    }
}

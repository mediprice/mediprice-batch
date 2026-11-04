package com.mediprice.batch.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.web.client.RestClient;

import com.mediprice.batch.HiraBatchRunner;
import com.mediprice.batch.hira.HiraClient;
import com.mediprice.batch.hira.HiraPage;
import com.mediprice.batch.hira.HiraProperties;

@SpringBootTest(classes = CleanupFlowTest.TestApp.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:${random.uuid};DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.batch.jdbc.initialize-schema=always",
        "spring.batch.job.enabled=false"
})
@ExtendWith(OutputCaptureExtension.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class CleanupFlowTest {

    static final String HOSP = "/hospInfoServicev2/getHospBasisList";
    static final String PRICE = "/nonPaymentDamtInfoService/getNonPaymentItemHospDtlList";
    static final int N = 2500;
    static final LocalDateTime W1 = LocalDateTime.of(2026, 1, 5, 3, 0);

    static OffsetDateTime now;
    static int failPriceAtChunk = -1;
    static int priceChunks;
    static Set<String> items = new HashSet<>();

    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired JobOperator jobOperator;
    @Autowired Job hiraSyncJob;
    @Autowired FakeHira hira;

    @BeforeEach
    void setUp() {
        var d = jdbc.getJdbcOperations();
        d.execute("CREATE TABLE region_sido (sido_cd varchar primary key, synced_at timestamp with time zone not null)");
        d.execute("CREATE TABLE region_sggu (sggu_cd varchar primary key, sido_cd varchar references region_sido (sido_cd), synced_at timestamp with time zone not null)");
        d.execute("CREATE TABLE institution_class (cl_cd varchar primary key, synced_at timestamp with time zone not null)");
        d.execute("CREATE TABLE npay_item (npay_cd varchar primary key, synced_at timestamp with time zone not null)");
        d.execute("CREATE TABLE hospital (ykiho varchar primary key, synced_at timestamp with time zone not null)");
        d.execute("CREATE TABLE hospital_detail (ykiho varchar primary key references hospital (ykiho) on delete cascade, synced_at timestamp with time zone not null)");
        d.execute("CREATE TABLE hospital_npay_price (ykiho varchar not null references hospital (ykiho) on delete cascade, sno int not null, npay_cd varchar not null references npay_item (npay_cd), synced_at timestamp with time zone not null, primary key (ykiho, sno))");

        failPriceAtChunk = -1;
        items = new HashSet<>(Set.of("i1"));
        hira.rows.clear();
        hira.before.clear();
        hira.calls.clear();
        List<Map<String, String>> hospitals = new ArrayList<>();
        List<Map<String, String>> prices = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            hospitals.add(Map.of("ykiho", "h" + i));
            prices.add(price("h" + i, 1, "i1"));
        }
        hira.rows.put(HOSP, hospitals);
        hira.rows.put(PRICE, prices);
    }

    static Map<String, String> price(String ykiho, int sno, String npayCd) {
        return Map.of("ykiho", ykiho, "sno", String.valueOf(sno), "npayCd", npayCd);
    }

    JobParameters params(LocalDateTime requestedAt) {
        return new JobParametersBuilder().addLocalDateTime(HiraBatchRunner.REQUESTED_AT, requestedAt).toJobParameters();
    }

    JobExecution run(LocalDateTime requestedAt) throws Exception {
        now = requestedAt.atZone(ZoneId.systemDefault()).toOffsetDateTime().plusMinutes(5);
        priceChunks = 0;
        return jobOperator.start(hiraSyncJob, params(requestedAt));
    }

    boolean hospitalExists(String ykiho) {
        return count("SELECT count(*) FROM hospital WHERE ykiho = :v", ykiho) == 1;
    }

    boolean priceExists(String ykiho, int sno) {
        return jdbc.queryForObject("SELECT count(*) FROM hospital_npay_price WHERE ykiho = :y AND sno = :s",
                new MapSqlParameterSource("y", ykiho).addValue("s", sno), Integer.class) == 1;
    }

    int count(String sql, String v) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource("v", v), Integer.class);
    }

    String failures(JobExecution e) {
        return e.getAllFailureExceptions().stream().map(t -> {
            StringBuilder s = new StringBuilder();
            for (Throwable c = t; c != null; c = c.getCause()) {
                s.append(c.getMessage()).append(" <- ");
            }
            return s.toString();
        }).collect(Collectors.joining(" | "));
    }

    void removeHospital(String ykiho) {
        hira.rows.get(HOSP).removeIf(m -> m.get("ykiho").equals(ykiho));
        hira.rows.get(PRICE).removeIf(m -> m.get("ykiho").equals(ykiho));
    }

    void insertSido(String sidoCd, OffsetDateTime syncedAt) {
        jdbc.update("INSERT INTO region_sido VALUES (:c, :t)",
                new MapSqlParameterSource("c", sidoCd).addValue("t", syncedAt));
    }

    void insertSggu(String sgguCd, String sidoCd, OffsetDateTime syncedAt) {
        jdbc.update("INSERT INTO region_sggu VALUES (:c, :s, :t)",
                new MapSqlParameterSource("c", sgguCd).addValue("s", sidoCd).addValue("t", syncedAt));
    }

    @Test
    void 첫_실행은_직전_성공이_없어_아무것도_안_지우고_전체건수를_Job기록에_올림() throws Exception {
        jdbc.update("INSERT INTO hospital VALUES (:v, :t)", new MapSqlParameterSource("v", "old")
                .addValue("t", W1.minusWeeks(10).atZone(ZoneId.systemDefault()).toOffsetDateTime()));

        JobExecution e = run(W1);

        assertThat(e.getStatus()).as(failures(e)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(hospitalExists("old")).isTrue();
        assertThat(e.getExecutionContext().getInt("hospital.totalCount")).isEqualTo(N);
        assertThat(e.getExecutionContext().getInt("hospital_npay_price.totalCount")).isEqualTo(N);
    }

    @Test
    void 한_번_안_보인_병원은_남고_두_번_연속_안_보이면_가격_상세까지_삭제() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        jdbc.update("INSERT INTO hospital_detail VALUES (:v, :t)",
                new MapSqlParameterSource("v", "h5").addValue("t", now));

        removeHospital("h5");
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(hospitalExists("h5")).isTrue();
        assertThat(priceExists("h5", 1)).isTrue();

        assertThat(run(W1.plusWeeks(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(hospitalExists("h5")).isFalse();
        assertThat(priceExists("h5", 1)).isFalse();
        assertThat(count("SELECT count(*) FROM hospital_detail WHERE ykiho = :v", "h5")).isZero();
    }

    @Test
    void 비급여항목과_가격이_같이_사라져도_FK_오류_없이_같은_실행에서_삭제() throws Exception {
        items.add("i2");
        hira.rows.get(PRICE).add(price("h0", 2, "i2"));
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        items.remove("i2");
        hira.rows.get(PRICE).removeIf(m -> "i2".equals(m.get("npayCd")));
        JobExecution w2 = run(W1.plusWeeks(1));
        assertThat(w2.getStatus()).as(failures(w2)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM npay_item WHERE npay_cd = :v", "i2")).isEqualTo(1);

        JobExecution w3 = run(W1.plusWeeks(2));
        assertThat(w3.getStatus()).as(failures(w3)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM npay_item WHERE npay_cd = :v", "i2")).isZero();
        assertThat(priceExists("h0", 2)).isFalse();
    }

    @Test
    void 재시작해도_건너뛴_Step의_전체건수로_검사하고_restart로는_복구안되며_새실행으로_복구됨() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        // 병원 Step이 순서 변경으로 h1000을 놓치고, 가격 Step은 실패
        hira.before.put(HOSP + "#2", () -> Collections.swap(hira.rows.get(HOSP), 999, 1000));
        failPriceAtChunk = 2;
        LocalDateTime w2 = W1.plusWeeks(1);
        JobExecution first = run(w2);
        assertThat(first.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(first)).contains("simulated");

        int hospCalls = hira.calls.get(HOSP);
        int priceCalls = hira.calls.get(PRICE);
        failPriceAtChunk = -1;
        JobExecution second = jobOperator.start(hiraSyncJob, params(w2));
        assertThat(second.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(second)).contains("hospital 갱신된 행이 전체 건수보다 적음: 2499 / 2500");
        assertThat(hira.calls.get(HOSP)).as("병원 Step은 건너뜀").isEqualTo(hospCalls);
        assertThat(hira.calls.get(PRICE) - priceCalls).as("가격 Step은 2페이지부터 이어 읽음").isEqualTo(2);
        assertThat(hospitalExists("h1000")).isTrue();

        JobExecution third = jobOperator.start(hiraSyncJob, params(w2));
        assertThat(third.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(third)).contains("--restart 말고 새로 실행");

        JobExecution fresh = run(W1.plusWeeks(2));
        assertThat(fresh.getStatus()).as(failures(fresh)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(hospitalExists("h1000")).isTrue();
    }

    @Test
    void 정리_실패시_앞_테이블의_삭제도_되돌아감() throws Exception {
        hira.rows.get(PRICE).add(price("h0", 9, "i1"));
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        hira.rows.get(PRICE).removeIf(m -> "9".equals(m.get("sno")));
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h0", 9)).isTrue();

        // 이번 실행에서 (h0, 9)는 삭제 대상이지만, 뒤의 hospital 건수 검사가 실패
        hira.before.put(HOSP + "#2", () -> Collections.swap(hira.rows.get(HOSP), 999, 1000));
        JobExecution w3 = run(W1.plusWeeks(2));
        assertThat(w3.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(failures(w3)).contains("hospital 갱신된 행이 전체 건수보다 적음");
        assertThat(priceExists("h0", 9)).as("롤백").isTrue();

        JobExecution w4 = run(W1.plusWeeks(3));
        assertThat(w4.getStatus()).as(failures(w4)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h0", 9)).isFalse();
    }

    @Test
    void 실제_Step에서_읽는_중_앞쪽_행이_빠지면_처음부터_다시_읽어_밀린_행을_갱신() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        hira.before.put(HOSP + "#2", () -> removeHospital("h5"));
        LocalDateTime w2 = W1.plusWeeks(1);
        JobExecution e = run(w2);

        assertThat(e.getStatus()).as(failures(e)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(e.getExecutionContext().getInt("hospital.totalCount")).isEqualTo(N - 1);
        OffsetDateTime syncedAt = jdbc.queryForObject("SELECT synced_at FROM hospital WHERE ykiho = :v",
                new MapSqlParameterSource("v", "h1000"), OffsetDateTime.class);
        assertThat(syncedAt.toInstant()).isEqualTo(now.toInstant());
    }

    @Test
    void 삭제_기준은_마지막_시도가_아니라_마지막_성공_실행() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        removeHospital("h5");
        failPriceAtChunk = 1;
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.FAILED);

        failPriceAtChunk = -1;
        JobExecution w3 = run(W1.plusWeeks(2));
        assertThat(w3.getStatus()).as(failures(w3)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(hospitalExists("h5")).as("성공한 W1 이후로 첫 성공 실행이므로 유지").isTrue();

        JobExecution w4 = run(W1.plusWeeks(3));
        assertThat(w4.getStatus()).as(failures(w4)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(hospitalExists("h5")).isFalse();
    }

    @Test
    void 항목_목록에서만_빠지면_실패없이_남고_가격까지_빠지면_같이_삭제(CapturedOutput output) throws Exception {
        items.add("i2");
        hira.rows.get(PRICE).add(price("h0", 2, "i2"));
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        items.remove("i2");
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        JobExecution w3 = run(W1.plusWeeks(2));
        assertThat(w3.getStatus()).as(failures(w3)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM npay_item WHERE npay_cd = :v", "i2")).isEqualTo(1);
        assertThat(priceExists("h0", 2)).isTrue();
        assertThat(output.getOut()).contains("npay_item: 다른 테이블이 참조 중이라 1건을 남김");

        hira.rows.get(PRICE).removeIf(m -> "i2".equals(m.get("npayCd")));
        assertThat(run(W1.plusWeeks(3)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h0", 2)).isTrue();
        JobExecution w5 = run(W1.plusWeeks(4));
        assertThat(w5.getStatus()).as(failures(w5)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h0", 2)).isFalse();
        assertThat(count("SELECT count(*) FROM npay_item WHERE npay_cd = :v", "i2")).isZero();
    }

    @Test
    void 병원_목록에서만_빠지면_최신_가격이_CASCADE로_안_지워짐(CapturedOutput output) throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        hira.rows.get(HOSP).removeIf(m -> m.get("ykiho").equals("h5"));
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        JobExecution w3 = run(W1.plusWeeks(2));
        assertThat(w3.getStatus()).as(failures(w3)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h5", 1)).isTrue();
        assertThat(hospitalExists("h5")).isTrue();
        assertThat(output.getOut()).contains("hospital: 다른 테이블이 참조 중이라 1건을 남김");
    }

    @Test
    void 가격이_병원보다_한_번_늦게_빠져도_유예_후_함께_삭제() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        hira.rows.get(HOSP).removeIf(m -> m.get("ykiho").equals("h5"));
        assertThat(run(W1.plusWeeks(1)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        hira.rows.get(PRICE).removeIf(m -> m.get("ykiho").equals("h5"));
        assertThat(run(W1.plusWeeks(2)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h5", 1)).as("한 번 안 보인 가격은 유지").isTrue();

        assertThat(run(W1.plusWeeks(3)).getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(priceExists("h5", 1)).isFalse();
        assertThat(hospitalExists("h5")).isFalse();
    }

    @Test
    void 시도_목록에서만_빠지면_실패없이_남고_시군구도_없으면_삭제() throws Exception {
        assertThat(run(W1).getStatus()).isEqualTo(BatchStatus.COMPLETED);

        LocalDateTime w2 = W1.plusWeeks(1);
        OffsetDateTime old = W1.minusWeeks(1).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        OffsetDateTime fresh = w2.atZone(ZoneId.systemDefault()).toOffsetDateTime().plusMinutes(5);
        // 시도·시군구 Tasklet이 가짜라 직접 넣음. A는 최신 시군구가 참조, B는 최신, C는 오래된 시군구만 참조
        insertSido("A", old);
        insertSido("B", fresh);
        insertSido("C", old);
        insertSggu("x", "A", fresh);
        insertSggu("y", "B", fresh);
        insertSggu("z", "C", old);

        JobExecution e = run(w2);
        assertThat(e.getStatus()).as(failures(e)).isEqualTo(BatchStatus.COMPLETED);
        assertThat(count("SELECT count(*) FROM region_sido WHERE sido_cd = :v", "A")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM region_sggu WHERE sggu_cd = :v", "x")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM region_sido WHERE sido_cd = :v", "C")).isZero();
        assertThat(count("SELECT count(*) FROM region_sggu WHERE sggu_cd = :v", "z")).isZero();
    }

    static class FakeHira extends HiraClient {
        final Map<String, List<Map<String, String>>> rows = new HashMap<>();
        final Map<String, Runnable> before = new HashMap<>();
        final Map<String, Integer> calls = new HashMap<>();

        FakeHira() {
            super(RestClient.builder(), new HiraProperties("http://localhost", "test-key"));
        }

        @Override
        public HiraPage get(String path, Map<String, String> params) {
            int page = Integer.parseInt(params.get("pageNo"));
            int size = Integer.parseInt(params.get("numOfRows"));
            Runnable r = before.remove(path + "#" + page);
            if (r != null) {
                r.run();
            }
            calls.merge(path, 1, Integer::sum);
            List<Map<String, String>> all = rows.get(path);
            int from = Math.min((page - 1) * size, all.size());
            int to = Math.min(page * size, all.size());
            return new HiraPage(new ArrayList<>(all.subList(from, to)), all.size());
        }
    }

    static class TestHospitalWriter extends HospitalWriter {
        final NamedParameterJdbcTemplate jdbc;

        TestHospitalWriter(NamedParameterJdbcTemplate jdbc) {
            super(jdbc, null);
            this.jdbc = jdbc;
        }

        @Override
        public void write(Chunk<? extends Map<String, String>> chunk) {
            for (Map<String, String> item : chunk.getItems()) {
                jdbc.update("MERGE INTO hospital (ykiho, synced_at) KEY (ykiho) VALUES (:y, :t)",
                        new MapSqlParameterSource("y", item.get("ykiho")).addValue("t", now));
            }
        }
    }

    static class TestPriceWriter extends NpayPriceWriter {
        final NamedParameterJdbcTemplate jdbc;

        TestPriceWriter(NamedParameterJdbcTemplate jdbc) {
            super(jdbc, null);
            this.jdbc = jdbc;
        }

        @Override
        public void write(Chunk<? extends Map<String, String>> chunk) {
            if (++priceChunks == failPriceAtChunk) {
                throw new IllegalStateException("simulated DB error");
            }
            for (Map<String, String> item : chunk.getItems()) {
                jdbc.update("MERGE INTO hospital_npay_price (ykiho, sno, npay_cd, synced_at) KEY (ykiho, sno) VALUES (:y, :s, :c, :t)",
                        new MapSqlParameterSource("y", item.get("ykiho"))
                                .addValue("s", Integer.parseInt(item.get("sno")))
                                .addValue("c", item.get("npayCd"))
                                .addValue("t", now));
            }
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(HiraSyncJobConfig.class)
    static class TestApp {

        static <T extends Tasklet> T finished(Class<T> type) throws Exception {
            T tasklet = mock(type);
            when(tasklet.execute(any(), any())).thenReturn(RepeatStatus.FINISHED);
            return tasklet;
        }

        @Bean FakeHira hiraClient() {
            return new FakeHira();
        }

        @Bean HospitalWriter hospitalWriter(NamedParameterJdbcTemplate jdbc) {
            return new TestHospitalWriter(jdbc);
        }

        @Bean NpayPriceWriter npayPriceWriter(NamedParameterJdbcTemplate jdbc) {
            return new TestPriceWriter(jdbc);
        }

        @Bean NpayItemTasklet npayItemTasklet(NamedParameterJdbcTemplate jdbc) throws Exception {
            NpayItemTasklet tasklet = mock(NpayItemTasklet.class);
            when(tasklet.execute(any(), any())).thenAnswer(inv -> {
                for (String code : items) {
                    jdbc.update("MERGE INTO npay_item (npay_cd, synced_at) KEY (npay_cd) VALUES (:c, :t)",
                            new MapSqlParameterSource("c", code).addValue("t", now));
                }
                return RepeatStatus.FINISHED;
            });
            return tasklet;
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

        @Bean PriceHistoryTasklet priceHistoryTasklet() throws Exception {
            return finished(PriceHistoryTasklet.class);
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

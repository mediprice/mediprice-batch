package com.mediprice.batch.job;

import java.time.Clock;
import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.mediprice.batch.KoreaTime;

// 홈 화면 통계를 세어 data_stats 한 행(id = 1)에 upsert. 백엔드 /api/stats가 이 행을 읽음
// 값의 정의는 백엔드가 가격 테이블에서 직접 세던 값과 같음 (hospital 전체 행 수가 아니라 "가격이 있는" 병원·항목 수)
// data_date는 이 Step이 도는 날(한국 날짜) = hiraSyncJob의 가격 동기화·정리가 방금 성공한 날. 가격이 0건이면 null
//   synced_at은 값이 바뀐 시각이라 max(synced_at)으로는 기준일을 알 수 없음. 이 Step 뒤에는 VACUUM만 남고 그건 실패해도 성공으로 침
// 실패하면 Step을 실패시킴: 통계는 가격 데이터와 한 묶음이라 옛 값을 새 기준일처럼 두지 않고, 다시 실행하면 이 Step부터 이어서 끝낼 수 있음
@Component
public class DataStatsTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(DataStatsTasklet.class);

    static final String UPSERT_SQL = """
            INSERT INTO data_stats (id, hospital_count, npay_item_count, price_count, data_date, refreshed_at)
            SELECT 1, count(DISTINCT ykiho), count(DISTINCT npay_cd), count(*),
                   CASE WHEN count(*) = 0 THEN NULL ELSE CAST(:today AS date) END, now()
            FROM hospital_npay_price
            ON CONFLICT (id) DO UPDATE
            SET hospital_count  = EXCLUDED.hospital_count,
                npay_item_count = EXCLUDED.npay_item_count,
                price_count     = EXCLUDED.price_count,
                data_date       = EXCLUDED.data_date,
                refreshed_at    = EXCLUDED.refreshed_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public DataStatsTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, KoreaTime.clock());
    }

    DataStatsTasklet(NamedParameterJdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        jdbcTemplate.update(UPSERT_SQL, new MapSqlParameterSource("today", LocalDate.now(clock)));
        log.info("data_stats 갱신 완료");
        return RepeatStatus.FINISHED;
    }
}

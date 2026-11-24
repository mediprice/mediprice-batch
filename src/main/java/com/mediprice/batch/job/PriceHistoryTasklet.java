package com.mediprice.batch.job;

import java.time.LocalDate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.mediprice.batch.HiraBatchRunner;

// 현재 가격(hospital_npay_price) 전체를 이번 달 기록으로 npay_price_history에 맞춤
// 같은 달에 다시 돌면 그 달 기록 중 바뀐 행만 고치고, 새 행은 추가하고, 현재 가격에 없는 행은 지움
// (예전처럼 그 달 전체를 지우고 다시 넣으면 매주 28만 행을 새로 써서 테이블·인덱스가 계속 불어남)
// HIRA에서 사라진 가격도 정리 단계에서 지워지기 전까지는 같이 기록됨
// 세 문장은 키 (ykiho, npay_cd, snapshot_month, sno)로 맞추므로 이력 PK가 예전 (snapshot_month, ykiho, sno)이어도 똑같이 동작함
@Component
public class PriceHistoryTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(PriceHistoryTasklet.class);

    // 항목 코드가 바뀐 행도 여기서 지워지고 아래 INSERT로 새 코드로 들어감 (먼저 지워야 예전 PK와 충돌하지 않음)
    private static final String DELETE_MISSING_SQL = """
            DELETE FROM npay_price_history h
            WHERE h.snapshot_month = :month
              AND NOT EXISTS (SELECT 1 FROM hospital_npay_price p
                              WHERE p.ykiho = h.ykiho AND p.sno = h.sno AND p.npay_cd = h.npay_cd)
            """;

    private static final String UPDATE_CHANGED_SQL = """
            UPDATE npay_price_history h SET
                yadm_npay_cd_nm = p.yadm_npay_cd_nm,
                cur_amt         = p.cur_amt,
                synced_at       = now()
            FROM hospital_npay_price p
            WHERE h.snapshot_month = :month
              AND h.ykiho = p.ykiho AND h.npay_cd = p.npay_cd AND h.sno = p.sno
              AND (h.yadm_npay_cd_nm, h.cur_amt) IS DISTINCT FROM (p.yadm_npay_cd_nm, p.cur_amt)
            """;

    // 한 달 안에서는 PK (ykiho, npay_cd, snapshot_month, sno) 순서로 넣어 같은 병원·항목 자리에 차례로 붙게 함
    // 단 새 PK에서는 새 달 행이 기존 달 행 사이(병원·항목별 자리)에 끼어 들어가 페이지 분할로 인덱스가 조금씩 부풀 수 있음
    // 커지면 가끔 REINDEX INDEX CONCURRENTLY npay_price_history_pkey; 로 회수 (읽기·쓰기를 막지 않음)
    private static final String INSERT_NEW_SQL = """
            INSERT INTO npay_price_history (
                snapshot_month, ykiho, sno, npay_cd, yadm_npay_cd_nm, cur_amt, synced_at)
            SELECT :month, ykiho, sno, npay_cd, yadm_npay_cd_nm, cur_amt, now()
            FROM hospital_npay_price
            ORDER BY ykiho, npay_cd, sno
            ON CONFLICT DO NOTHING
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PriceHistoryTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 기준 월 = 실행 기준 시각(requestedAt)이 속한 달의 1일. 이어서 실행해도 같은 달로 기록됨
        LocalDate month = HiraBatchRunner.requestedAt(chunkContext).toLocalDate().withDayOfMonth(1);

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("month", month);

        int deleted = jdbcTemplate.update(DELETE_MISSING_SQL, params);
        int updated = jdbcTemplate.update(UPDATE_CHANGED_SQL, params);
        int inserted = jdbcTemplate.update(INSERT_NEW_SQL, params);
        log.info("{} 가격 이력: 추가 {}건, 갱신 {}건, 삭제 {}건", month, inserted, updated, deleted);

        contribution.incrementWriteCount(inserted + updated);
        return RepeatStatus.FINISHED;
    }
}

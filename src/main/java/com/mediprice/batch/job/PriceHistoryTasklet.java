package com.mediprice.batch.job;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import com.mediprice.batch.HiraBatchRunner;

// 현재 가격(hospital_npay_price) 전체를 이번 달 기록으로 npay_price_history에 복사
// 같은 달에 다시 돌면 그 달 기록을 지우고 새로 채움. HIRA에서 사라진 가격도 정리 단계에서 지워지기 전까지는 같이 복사됨
@Component
public class PriceHistoryTasklet implements Tasklet {

    private static final String DELETE_MONTH_SQL = """
            DELETE FROM npay_price_history WHERE snapshot_month = :month
            """;

    private static final String COPY_SQL = """
            INSERT INTO npay_price_history (
                snapshot_month, ykiho, sno, npay_cd, yadm_npay_cd_nm, cur_amt, synced_at)
            SELECT :month, ykiho, sno, npay_cd, yadm_npay_cd_nm, cur_amt, now()
            FROM hospital_npay_price
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PriceHistoryTasklet(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 기준 월 = 실행 기준 시각(requestedAt)이 속한 달의 1일. 이어서 실행해도 같은 달로 기록됨
        LocalDateTime requestedAt = chunkContext.getStepContext().getStepExecution()
                .getJobParameters().getLocalDateTime(HiraBatchRunner.REQUESTED_AT);
        LocalDate month = (requestedAt != null ? requestedAt.toLocalDate() : LocalDate.now()).withDayOfMonth(1);

        MapSqlParameterSource params = new MapSqlParameterSource().addValue("month", month);

        jdbcTemplate.update(DELETE_MONTH_SQL, params);
        int copied = jdbcTemplate.update(COPY_SQL, params);

        contribution.incrementWriteCount(copied);
        return RepeatStatus.FINISHED;
    }
}

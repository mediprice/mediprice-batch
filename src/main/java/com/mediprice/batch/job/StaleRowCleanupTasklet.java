package com.mediprice.batch.job;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.mediprice.batch.HiraBatchRunner;

// 이번 실행에서 갱신되지 않은 행(HIRA에서 사라진 데이터)을 삭제
// 앞 Step들이 전부 성공해야 이 Step까지 오므로, 덜 받은 상태로 지우는 일은 없음
public class StaleRowCleanupTasklet implements Tasklet {

    private static final Logger log = LoggerFactory.getLogger(StaleRowCleanupTasklet.class);

    // 이 컴퓨터와 DB 서버의 시계 차이 여유. 주 1회 배치라 1시간 여유를 둬도 옛 행은 충분히 걸러짐
    private static final long CLOCK_MARGIN_HOURS = 1;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final List<String> tables; // FK 순서대로 (참조하는 테이블 먼저)

    public StaleRowCleanupTasklet(NamedParameterJdbcTemplate jdbcTemplate, List<String> tables) {
        this.jdbcTemplate = jdbcTemplate;
        this.tables = tables;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        LocalDateTime requestedAt = chunkContext.getStepContext().getStepExecution()
                .getJobParameters().getLocalDateTime(HiraBatchRunner.REQUESTED_AT);
        if (requestedAt == null) {
            throw new IllegalStateException("실행 파라미터 requestedAt이 없음 (HiraBatchRunner로 실행해야 함)");
        }
        OffsetDateTime cutoff = requestedAt.atZone(ZoneId.systemDefault()).toOffsetDateTime()
                .minusHours(CLOCK_MARGIN_HOURS);
        MapSqlParameterSource params = new MapSqlParameterSource("cutoff", cutoff);

        long totalDeleted = 0;
        for (String table : tables) {
            Integer fresh = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table + " WHERE synced_at >= :cutoff", params, Integer.class);
            // 갱신된 행이 하나도 없으면 다 지워지는 상황이라 이상함 → 건너뜀
            if (fresh == null || fresh == 0) {
                log.warn("{}: 이번 실행에서 갱신된 행이 없어 삭제를 건너뜀", table);
                continue;
            }
            int deleted = jdbcTemplate.update("DELETE FROM " + table + " WHERE synced_at < :cutoff", params);
            log.info("{}: 오래된 행 {}건 삭제 (남은 행 {}건)", table, deleted, fresh);
            totalDeleted += deleted;
        }

        contribution.incrementWriteCount(totalDeleted);
        return RepeatStatus.FINISHED;
    }
}

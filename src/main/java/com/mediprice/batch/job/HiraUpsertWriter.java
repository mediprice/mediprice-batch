package com.mediprice.batch.job;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.core.scope.context.StepSynchronizationManager;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import com.mediprice.batch.HiraBatchRunner;
import com.mediprice.batch.KoreaTime;

// item 묶음(chunk)을 한 번에 저장하는 공통 Writer. 테이블마다 SQL과 필드 목록만 다르게 넘김
// 같은 Job이 동시에 두 개 돌지 않는다는 전제(HiraBatchRunner가 막음). 동시에 돌면 *_seen 키와 값 비교가 서로 섞임
public class HiraUpsertWriter implements ItemWriter<Map<String, String>> {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String sql;
    private final List<String> fields;

    // SQL의 :ykiho 같은 파라미터 이름이 HIRA 응답 필드 이름과 같아야 함. 목록에 없는 응답 필드는 저장하지 않음
    // 실행 기준 시각은 :requestedAt으로 넘어감 (*_seen에 어느 실행에서 본 키인지 적을 때 씀)
    public HiraUpsertWriter(NamedParameterJdbcTemplate jdbcTemplate, String sql, List<String> fields) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
        this.fields = fields;
    }

    @Override
    public void write(Chunk<? extends Map<String, String>> chunk) {
        OffsetDateTime runAt = currentRunAt();
        SqlParameterSource[] batch = chunk.getItems().stream()
                .map(item -> toParams(item, runAt))
                .toArray(SqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(sql, batch);
    }

    // 지금 실행 중인 Step의 실행 기준 시각(requestedAt, Asia/Seoul). 이어서 실행해도 처음 실행과 같은 값
    protected static OffsetDateTime currentRunAt() {
        StepContext context = StepSynchronizationManager.getContext();
        if (context == null) {
            throw new IllegalStateException("Step 밖에서 Writer가 호출됨 (requestedAt을 알 수 없음)");
        }
        LocalDateTime requestedAt = context.getStepExecution().getJobParameters().getLocalDateTime(HiraBatchRunner.REQUESTED_AT);
        if (requestedAt == null) {
            throw new IllegalStateException("실행 파라미터 requestedAt이 없음 (HiraBatchRunner로 실행해야 함)");
        }
        return requestedAt.atZone(KoreaTime.ZONE).toOffsetDateTime();
    }

    private SqlParameterSource toParams(Map<String, String> item, OffsetDateTime runAt) {
        MapSqlParameterSource params = new MapSqlParameterSource("requestedAt", runAt);
        // 응답에서 빠진 필드는 null로 채움 (안 채우면 "값 없음" 오류)
        for (String field : fields) {
            params.addValue(field, item.get(field));
        }
        return params;
    }
}

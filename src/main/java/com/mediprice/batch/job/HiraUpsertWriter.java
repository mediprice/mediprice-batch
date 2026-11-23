package com.mediprice.batch.job;

import java.util.List;
import java.util.Map;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

// item 묶음(chunk)을 한 번에 저장하는 공통 Writer. 테이블마다 SQL과 필드 목록만 다르게 넘김
public class HiraUpsertWriter implements ItemWriter<Map<String, String>> {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final String sql;
    private final List<String> fields;

    // SQL의 :ykiho 같은 파라미터 이름이 HIRA 응답 필드 이름과 같아야 함. 목록에 없는 응답 필드는 저장하지 않음
    public HiraUpsertWriter(NamedParameterJdbcTemplate jdbcTemplate, String sql, List<String> fields) {
        this.jdbcTemplate = jdbcTemplate;
        this.sql = sql;
        this.fields = fields;
    }

    @Override
    public void write(Chunk<? extends Map<String, String>> chunk) {
        SqlParameterSource[] batch = chunk.getItems().stream()
                .map(this::toParams)
                .toArray(SqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(sql, batch);
    }

    private SqlParameterSource toParams(Map<String, String> item) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        // 응답에서 빠진 필드는 null로 채움 (안 채우면 "값 없음" 오류)
        for (String field : fields) {
            params.addValue(field, item.get(field));
        }
        return params;
    }
}

package com.mediprice.batch.job;

import java.util.List;
import java.util.Map;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import tools.jackson.databind.json.JsonMapper;

// item 묶음(chunk)을 한 번에 upsert하는 공통 Writer. 테이블마다 SQL과 필드 목록만 다르게 넘김
public class HiraUpsertWriter implements ItemWriter<Map<String, String>> {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final String sql;
    private final List<String> fields;

    // sql : :ykiho 같은 파라미터 이름이 HIRA 응답 필드 이름과 같아야 함. 원본은 :raw로 넘어감
    // fields : SQL에 넘길 HIRA 응답 필드 이름 목록
    public HiraUpsertWriter(NamedParameterJdbcTemplate jdbcTemplate, JsonMapper jsonMapper,
                            String sql, List<String> fields) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
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
        params.addValue("raw", jsonMapper.writeValueAsString(item));
        return params;
    }
}

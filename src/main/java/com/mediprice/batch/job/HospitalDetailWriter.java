package com.mediprice.batch.job;

import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import com.mediprice.batch.job.HospitalDetailProcessor.DetailRow;

import tools.jackson.databind.json.JsonMapper;

// 병원 세부정보 → hospital_detail upsert. 응답 item을 통째로 detail(jsonb)에 저장
@Component
public class HospitalDetailWriter implements ItemWriter<DetailRow> {

    private static final String UPSERT_SQL = """
            INSERT INTO hospital_detail (ykiho, detail, synced_at)
            VALUES (:ykiho, CAST(:detail AS jsonb), now())
            ON CONFLICT (ykiho) DO UPDATE
            SET detail    = EXCLUDED.detail,
                synced_at = now()
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public HospitalDetailWriter(NamedParameterJdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void write(Chunk<? extends DetailRow> chunk) {
        SqlParameterSource[] batch = chunk.getItems().stream()
                .map(row -> new MapSqlParameterSource()
                        .addValue("ykiho", row.ykiho())
                        .addValue("detail", row.detail() == null ? null : jsonMapper.writeValueAsString(row.detail())))
                .toArray(SqlParameterSource[]::new);
        jdbcTemplate.batchUpdate(UPSERT_SQL, batch);
    }
}

package com.mediprice.batch.job;

import java.util.Map;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Component;

import com.mediprice.batch.hira.HiraClient;
import com.mediprice.batch.hira.HiraPage;

// 종별 코드 → institution_class 저장 (병원코드정보서비스 getMedicInsttClassesCodeList)
@Component
public class InstitutionClassTasklet implements Tasklet {

    private static final String UPSERT_SQL = """
            INSERT INTO institution_class (cl_cd, cl_cd_nm, synced_at)
            VALUES (:clCd, :clCdNm, now())
            ON CONFLICT (cl_cd) DO UPDATE
            SET cl_cd_nm  = EXCLUDED.cl_cd_nm,
                synced_at = now()
            """;

    private final HiraClient hiraClient;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public InstitutionClassTasklet(HiraClient hiraClient, NamedParameterJdbcTemplate jdbcTemplate) {
        this.hiraClient = hiraClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        HiraPage page = hiraClient.get("/codeInfoService/getMedicInsttClassesCodeList",
                Map.of("pageNo", "1", "numOfRows", "1000"));

        if (page.items().size() != page.totalCount()) {
            throw new IllegalStateException("종별 코드를 다 받지 못함: "
                    + page.items().size() + " / " + page.totalCount());
        }

        SqlParameterSource[] batch = page.items().stream()
                .map(item -> new MapSqlParameterSource()
                        .addValue("clCd", item.get("clCd"))
                        .addValue("clCdNm", item.get("clCdNm")))
                .toArray(SqlParameterSource[]::new);

        jdbcTemplate.batchUpdate(UPSERT_SQL, batch);

        contribution.incrementWriteCount(batch.length);
        return RepeatStatus.FINISHED;
    }
}

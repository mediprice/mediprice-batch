package com.mediprice.batch.job;

import java.util.ArrayList;
import java.util.List;
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

// 시군구 코드 → region_sggu 저장. 시도마다 1번씩 호출 (병원코드정보서비스 getAddrCodeList, addrTp=2)
// region_sido가 먼저 저장돼 있어야 함 (FK)
@Component
public class SgguCodeTasklet implements Tasklet {

    private static final String UPSERT_SQL = """
            INSERT INTO region_sggu (sggu_cd, sido_cd, sggu_cd_nm, synced_at)
            VALUES (:sgguCd, :sidoCd, :sgguCdNm, now())
            ON CONFLICT (sggu_cd) DO UPDATE
            SET sido_cd    = EXCLUDED.sido_cd,
                sggu_cd_nm = EXCLUDED.sggu_cd_nm,
                synced_at  = now()
            """;

    private final HiraClient hiraClient;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SgguCodeTasklet(HiraClient hiraClient, NamedParameterJdbcTemplate jdbcTemplate) {
        this.hiraClient = hiraClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        List<String> sidoCodes = jdbcTemplate.getJdbcOperations()
                .queryForList("SELECT sido_cd FROM region_sido ORDER BY sido_cd", String.class);

        List<SqlParameterSource> batch = new ArrayList<>();
        for (String sidoCd : sidoCodes) {
            HiraPage page = hiraClient.get("/codeInfoService/getAddrCodeList",
                    Map.of("addrTp", "2", "sidoCd", sidoCd, "pageNo", "1", "numOfRows", "1000"));

            if (page.items().size() != page.totalCount()) {
                throw new IllegalStateException("시군구 코드를 다 받지 못함 (시도 " + sidoCd + "): "
                        + page.items().size() + " / " + page.totalCount());
            }

            for (Map<String, String> item : page.items()) {
                batch.add(new MapSqlParameterSource()
                        .addValue("sgguCd", item.get("addrCd"))
                        .addValue("sidoCd", sidoCd)
                        .addValue("sgguCdNm", item.get("addrCdNm")));
            }
        }

        jdbcTemplate.batchUpdate(UPSERT_SQL, batch.toArray(SqlParameterSource[]::new));

        contribution.incrementWriteCount(batch.size());
        return RepeatStatus.FINISHED;
    }
}

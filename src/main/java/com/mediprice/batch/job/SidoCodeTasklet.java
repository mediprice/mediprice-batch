package com.mediprice.batch.job;

import java.util.Map;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.mediprice.batch.hira.HiraClient;
import com.mediprice.batch.hira.HiraPage;

// 시도 코드 → region_sido 저장 (병원코드정보서비스 getAddrCodeList, addrTp=1)
@Component
public class SidoCodeTasklet implements Tasklet {

    private static final String UPSERT_SQL = """
            INSERT INTO region_sido (sido_cd, sido_cd_nm, synced_at)
            VALUES (:sidoCd, :sidoCdNm, now())
            ON CONFLICT (sido_cd) DO UPDATE
            SET sido_cd_nm = EXCLUDED.sido_cd_nm,
                synced_at  = now()
            """;

    private final HiraClient hiraClient;
    private final JdbcClient jdbcClient;

    public SidoCodeTasklet(HiraClient hiraClient, JdbcClient jdbcClient) {
        this.hiraClient = hiraClient;
        this.jdbcClient = jdbcClient;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        HiraPage page = hiraClient.get("/codeInfoService/getAddrCodeList",
                Map.of("addrTp", "1", "pageNo", "1", "numOfRows", "1000"));

        if (page.items().size() != page.totalCount()) {
            throw new IllegalStateException("시도 코드를 다 받지 못함: "
                    + page.items().size() + " / " + page.totalCount());
        }

        for (Map<String, String> item : page.items()) {
            // synced_at은 UPDATE 때도 직접 now()로 갱신 (DEFAULT는 INSERT 때만 적용)
            jdbcClient.sql(UPSERT_SQL)
                    .param("sidoCd", item.get("addrCd"))
                    .param("sidoCdNm", item.get("addrCdNm"))
                    .update();
        }

        contribution.incrementWriteCount(page.items().size());
        return RepeatStatus.FINISHED;
    }
}

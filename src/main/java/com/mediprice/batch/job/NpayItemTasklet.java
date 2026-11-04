package com.mediprice.batch.job;

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

import tools.jackson.databind.json.JsonMapper;

// 비급여 항목 → npay_item 저장 (비급여 3번 getNonPaymentItemCodeList2)
@Component
public class NpayItemTasklet implements Tasklet {

    // SQL 파라미터(:npayCd 등) 이름을 HIRA 응답 필드 이름과 같게 맞춤 → item Map에서 바로 꺼내 씀
    private static final List<String> FIELDS = List.of(
            "npayCd", "npayKorNm",
            "npayMdivCd", "npayMdivCdNm",
            "npaySdivCd", "npaySdivCdNm",
            "npayDtlDivCd", "npayDtlDivCdNm",
            "cmmtTxt", "adtFrDd", "adtEndDd");

    // 날짜는 "20241126" 글자로 와서 to_date로 변환. 빈 값이면 NULL
    private static final String UPSERT_SQL = """
            INSERT INTO npay_item (
                npay_cd, npay_kor_nm,
                npay_mdiv_cd, npay_mdiv_cd_nm,
                npay_sdiv_cd, npay_sdiv_cd_nm,
                npay_dtl_div_cd, npay_dtl_div_cd_nm,
                cmmt_txt, adt_fr_dd, adt_end_dd,
                raw, synced_at)
            VALUES (
                :npayCd, :npayKorNm,
                :npayMdivCd, :npayMdivCdNm,
                :npaySdivCd, :npaySdivCdNm,
                :npayDtlDivCd, :npayDtlDivCdNm,
                :cmmtTxt,
                to_date(NULLIF(:adtFrDd, ''), 'YYYYMMDD'),
                to_date(NULLIF(:adtEndDd, ''), 'YYYYMMDD'),
                CAST(:raw AS jsonb), now())
            ON CONFLICT (npay_cd) DO UPDATE SET
                npay_kor_nm        = EXCLUDED.npay_kor_nm,
                npay_mdiv_cd       = EXCLUDED.npay_mdiv_cd,
                npay_mdiv_cd_nm    = EXCLUDED.npay_mdiv_cd_nm,
                npay_sdiv_cd       = EXCLUDED.npay_sdiv_cd,
                npay_sdiv_cd_nm    = EXCLUDED.npay_sdiv_cd_nm,
                npay_dtl_div_cd    = EXCLUDED.npay_dtl_div_cd,
                npay_dtl_div_cd_nm = EXCLUDED.npay_dtl_div_cd_nm,
                cmmt_txt           = EXCLUDED.cmmt_txt,
                adt_fr_dd          = EXCLUDED.adt_fr_dd,
                adt_end_dd         = EXCLUDED.adt_end_dd,
                raw                = EXCLUDED.raw,
                synced_at          = now()
            """;

    private final HiraClient hiraClient;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public NpayItemTasklet(HiraClient hiraClient, NamedParameterJdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.hiraClient = hiraClient;
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        HiraPage page = hiraClient.get("/nonPaymentDamtInfoService/getNonPaymentItemCodeList2",
                Map.of("pageNo", "1", "numOfRows", "1000"));

        // 항목이 1000개를 넘으면 페이지 나눠 받도록 바꿔야 함
        if (page.items().size() != page.totalCount()) {
            throw new IllegalStateException("비급여 항목을 다 받지 못함: "
                    + page.items().size() + " / " + page.totalCount());
        }

        SqlParameterSource[] batch = page.items().stream()
                .map(this::toParams)
                .toArray(SqlParameterSource[]::new);

        // 여러 행을 묶어서 한 번에 전송 (한 줄씩 보내면 Neon 왕복 시간 때문에 느림)
        jdbcTemplate.batchUpdate(UPSERT_SQL, batch);

        contribution.incrementWriteCount(batch.length);
        return RepeatStatus.FINISHED;
    }

    private SqlParameterSource toParams(Map<String, String> item) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        // 응답에서 빠진 필드는 null로 채움 (안 채우면 "값 없음" 오류)
        for (String field : FIELDS) {
            params.addValue(field, item.get(field));
        }
        params.addValue("raw", jsonMapper.writeValueAsString(item));
        return params;
    }
}

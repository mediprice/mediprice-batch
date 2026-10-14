package com.mediprice.batch.job;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

// 병원별 비급여 가격 → hospital_npay_price upsert (비급여 5번 getNonPaymentItemHospDtlList)
@Component
public class NpayPriceWriter extends HiraUpsertWriter {

    private static final List<String> FIELDS = List.of(
            "ykiho", "sno", "npayCd", "yadmNpayCdNm",
            "curAmt", "adtFrDd", "adtEndDd");

    // 병원명·지역 등 병원 정보는 hospital 테이블에 있어서 raw에만 남김
    private static final String UPSERT_SQL = """
            INSERT INTO hospital_npay_price (
                ykiho, sno, npay_cd, yadm_npay_cd_nm,
                cur_amt, adt_fr_dd, adt_end_dd,
                raw, synced_at)
            VALUES (
                :ykiho, CAST(:sno AS integer), :npayCd, :yadmNpayCdNm,
                CAST(:curAmt AS bigint),
                to_date(NULLIF(:adtFrDd, ''), 'YYYYMMDD'),
                to_date(NULLIF(:adtEndDd, ''), 'YYYYMMDD'),
                CAST(:raw AS jsonb), now())
            ON CONFLICT (ykiho, sno) DO UPDATE SET
                npay_cd         = EXCLUDED.npay_cd,
                yadm_npay_cd_nm = EXCLUDED.yadm_npay_cd_nm,
                cur_amt         = EXCLUDED.cur_amt,
                adt_fr_dd       = EXCLUDED.adt_fr_dd,
                adt_end_dd      = EXCLUDED.adt_end_dd,
                raw             = EXCLUDED.raw,
                synced_at       = now()
            """;

    public NpayPriceWriter(NamedParameterJdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        super(jdbcTemplate, jsonMapper, UPSERT_SQL, FIELDS);
    }
}

package com.mediprice.batch.job;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 병원별 비급여 가격 → hospital_npay_price (비급여 5번 getNonPaymentItemHospDtlList)
// 병원명·지역·항목명은 hospital, npay_item에 있어서 저장하지 않음. urlAddr(병원 홈페이지)는 읽는 곳이 없어 버림
@Component
public class NpayPriceWriter extends HiraUpsertWriter {

    private static final List<String> FIELDS = List.of(
            "ykiho", "sno", "npayCd", "yadmNpayCdNm",
            "curAmt", "adtFrDd", "adtEndDd");

    // 한 문장으로 세 가지를 함 (값 변환은 v에서 한 번만)
    //  1. seen: 이번 실행에 본 키를 실행 기준 시각과 함께 적음. 정리 Step이 이걸로 사라진 행을 판단함
    //  2. added: 없던 행 추가. 이미 있으면 아무것도 안 함
    //  3. UPDATE: 값이 바뀐 행만 갱신. 같으면 행을 다시 쓰지 않음 (매주 28만 행 재기록과 인덱스 bloat 방지)
    // ON CONFLICT DO UPDATE ... WHERE는 값이 같아도 충돌한 행마다 행 잠금을 기록해서(WAL) 쓰지 않음
    private static final String UPSERT_SQL = """
            WITH v AS (
                SELECT CAST(:ykiho AS text) AS ykiho,
                       CAST(:sno AS integer) AS sno,
                       CAST(:npayCd AS text) AS npay_cd,
                       CAST(:yadmNpayCdNm AS text) AS yadm_npay_cd_nm,
                       CAST(:curAmt AS bigint) AS cur_amt,
                       to_date(NULLIF(:adtFrDd, ''), 'YYYYMMDD') AS adt_fr_dd,
                       to_date(NULLIF(:adtEndDd, ''), 'YYYYMMDD') AS adt_end_dd
            ), seen AS (
                INSERT INTO hospital_npay_price_seen (ykiho, sno, requested_at)
                SELECT ykiho, sno, CAST(:requestedAt AS timestamptz) FROM v
            ), added AS (
                INSERT INTO hospital_npay_price (
                    ykiho, sno, npay_cd, yadm_npay_cd_nm,
                    cur_amt, adt_fr_dd, adt_end_dd, synced_at)
                SELECT ykiho, sno, npay_cd, yadm_npay_cd_nm,
                       cur_amt, adt_fr_dd, adt_end_dd, now()
                FROM v
                ON CONFLICT (ykiho, sno) DO NOTHING
            )
            UPDATE hospital_npay_price p SET
                npay_cd         = v.npay_cd,
                yadm_npay_cd_nm = v.yadm_npay_cd_nm,
                cur_amt         = v.cur_amt,
                adt_fr_dd       = v.adt_fr_dd,
                adt_end_dd      = v.adt_end_dd,
                synced_at       = now()
            FROM v
            WHERE p.ykiho = v.ykiho AND p.sno = v.sno
              AND (p.npay_cd, p.yadm_npay_cd_nm, p.cur_amt, p.adt_fr_dd, p.adt_end_dd)
                  IS DISTINCT FROM (v.npay_cd, v.yadm_npay_cd_nm, v.cur_amt, v.adt_fr_dd, v.adt_end_dd)
            """;

    public NpayPriceWriter(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate, UPSERT_SQL, FIELDS);
    }
}

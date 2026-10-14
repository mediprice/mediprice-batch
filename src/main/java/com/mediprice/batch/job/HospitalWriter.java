package com.mediprice.batch.job;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

// 병원 기본정보 → hospital upsert (병원정보서비스 getHospBasisList)
@Component
public class HospitalWriter extends HiraUpsertWriter {

    private static final List<String> FIELDS = List.of(
            "ykiho", "yadmNm", "clCd", "clCdNm",
            "sidoCd", "sidoCdNm", "sgguCd", "sgguCdNm",
            "emdongNm", "postNo", "addr", "telno", "hospUrl",
            "estbDd", "drTotCnt", "XPos", "YPos");

    // 글자로 오는 날짜·숫자·좌표는 DB에서 변환. 빈 값이면 NULL
    // geog(위치)는 x_pos, y_pos로 DB가 자동 계산해서 넣지 않음
    private static final String UPSERT_SQL = """
            INSERT INTO hospital (
                ykiho, yadm_nm, cl_cd, cl_cd_nm,
                sido_cd, sido_cd_nm, sggu_cd, sggu_cd_nm,
                emdong_nm, post_no, addr, telno, hosp_url,
                estb_dd, dr_tot_cnt, x_pos, y_pos,
                raw, synced_at)
            VALUES (
                :ykiho, :yadmNm, :clCd, :clCdNm,
                :sidoCd, :sidoCdNm, :sgguCd, :sgguCdNm,
                :emdongNm, :postNo, :addr, :telno, :hospUrl,
                to_date(NULLIF(:estbDd, ''), 'YYYYMMDD'),
                CAST(NULLIF(:drTotCnt, '') AS integer),
                CAST(NULLIF(:XPos, '') AS double precision),
                CAST(NULLIF(:YPos, '') AS double precision),
                CAST(:raw AS jsonb), now())
            ON CONFLICT (ykiho) DO UPDATE SET
                yadm_nm    = EXCLUDED.yadm_nm,
                cl_cd      = EXCLUDED.cl_cd,
                cl_cd_nm   = EXCLUDED.cl_cd_nm,
                sido_cd    = EXCLUDED.sido_cd,
                sido_cd_nm = EXCLUDED.sido_cd_nm,
                sggu_cd    = EXCLUDED.sggu_cd,
                sggu_cd_nm = EXCLUDED.sggu_cd_nm,
                emdong_nm  = EXCLUDED.emdong_nm,
                post_no    = EXCLUDED.post_no,
                addr       = EXCLUDED.addr,
                telno      = EXCLUDED.telno,
                hosp_url   = EXCLUDED.hosp_url,
                estb_dd    = EXCLUDED.estb_dd,
                dr_tot_cnt = EXCLUDED.dr_tot_cnt,
                x_pos      = EXCLUDED.x_pos,
                y_pos      = EXCLUDED.y_pos,
                raw        = EXCLUDED.raw,
                synced_at  = now()
            """;

    public HospitalWriter(NamedParameterJdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        super(jdbcTemplate, jsonMapper, UPSERT_SQL, FIELDS);
    }
}

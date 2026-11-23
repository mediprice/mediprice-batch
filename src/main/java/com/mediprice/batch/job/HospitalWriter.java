package com.mediprice.batch.job;

import java.util.List;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 병원 기본정보 → hospital (병원정보서비스 getHospBasisList)
// 과별 인원수(mdept*, cmdc*, dety*, pnursCnt) 등 목록에 없는 응답 필드는 읽는 곳이 없어 저장하지 않음
@Component
public class HospitalWriter extends HiraUpsertWriter {

    private static final List<String> FIELDS = List.of(
            "ykiho", "yadmNm", "clCd", "clCdNm",
            "sidoCd", "sidoCdNm", "sgguCd", "sgguCdNm",
            "emdongNm", "postNo", "addr", "telno", "hospUrl",
            "estbDd", "drTotCnt", "XPos", "YPos");

    // NpayPriceWriter와 같은 방식: 본 키 기록 → 없던 행 추가 → 값이 바뀐 행만 갱신
    // 글자로 오는 날짜·숫자·좌표는 DB에서 변환. 빈 값이면 NULL
    // geog(위치)는 x_pos, y_pos로 DB가 자동 계산해서 넣지 않음
    private static final String UPSERT_SQL = """
            WITH v AS (
                SELECT CAST(:ykiho AS text) AS ykiho,
                       CAST(:yadmNm AS text) AS yadm_nm,
                       CAST(:clCd AS text) AS cl_cd,
                       CAST(:clCdNm AS text) AS cl_cd_nm,
                       CAST(:sidoCd AS text) AS sido_cd,
                       CAST(:sidoCdNm AS text) AS sido_cd_nm,
                       CAST(:sgguCd AS text) AS sggu_cd,
                       CAST(:sgguCdNm AS text) AS sggu_cd_nm,
                       CAST(:emdongNm AS text) AS emdong_nm,
                       CAST(:postNo AS text) AS post_no,
                       CAST(:addr AS text) AS addr,
                       CAST(:telno AS text) AS telno,
                       CAST(:hospUrl AS text) AS hosp_url,
                       to_date(NULLIF(:estbDd, ''), 'YYYYMMDD') AS estb_dd,
                       CAST(NULLIF(:drTotCnt, '') AS integer) AS dr_tot_cnt,
                       CAST(NULLIF(:XPos, '') AS double precision) AS x_pos,
                       CAST(NULLIF(:YPos, '') AS double precision) AS y_pos
            ), seen AS (
                INSERT INTO hospital_seen (ykiho)
                SELECT ykiho FROM v
            ), added AS (
                INSERT INTO hospital (
                    ykiho, yadm_nm, cl_cd, cl_cd_nm,
                    sido_cd, sido_cd_nm, sggu_cd, sggu_cd_nm,
                    emdong_nm, post_no, addr, telno, hosp_url,
                    estb_dd, dr_tot_cnt, x_pos, y_pos, synced_at)
                SELECT ykiho, yadm_nm, cl_cd, cl_cd_nm,
                       sido_cd, sido_cd_nm, sggu_cd, sggu_cd_nm,
                       emdong_nm, post_no, addr, telno, hosp_url,
                       estb_dd, dr_tot_cnt, x_pos, y_pos, now()
                FROM v
                ON CONFLICT (ykiho) DO NOTHING
            )
            UPDATE hospital h SET
                yadm_nm    = v.yadm_nm,
                cl_cd      = v.cl_cd,
                cl_cd_nm   = v.cl_cd_nm,
                sido_cd    = v.sido_cd,
                sido_cd_nm = v.sido_cd_nm,
                sggu_cd    = v.sggu_cd,
                sggu_cd_nm = v.sggu_cd_nm,
                emdong_nm  = v.emdong_nm,
                post_no    = v.post_no,
                addr       = v.addr,
                telno      = v.telno,
                hosp_url   = v.hosp_url,
                estb_dd    = v.estb_dd,
                dr_tot_cnt = v.dr_tot_cnt,
                x_pos      = v.x_pos,
                y_pos      = v.y_pos,
                synced_at  = now()
            FROM v
            WHERE h.ykiho = v.ykiho
              AND (h.yadm_nm, h.cl_cd, h.cl_cd_nm, h.sido_cd, h.sido_cd_nm, h.sggu_cd, h.sggu_cd_nm,
                   h.emdong_nm, h.post_no, h.addr, h.telno, h.hosp_url, h.estb_dd, h.dr_tot_cnt, h.x_pos, h.y_pos)
                  IS DISTINCT FROM
                  (v.yadm_nm, v.cl_cd, v.cl_cd_nm, v.sido_cd, v.sido_cd_nm, v.sggu_cd, v.sggu_cd_nm,
                   v.emdong_nm, v.post_no, v.addr, v.telno, v.hosp_url, v.estb_dd, v.dr_tot_cnt, v.x_pos, v.y_pos)
            """;

    public HospitalWriter(NamedParameterJdbcTemplate jdbcTemplate) {
        super(jdbcTemplate, UPSERT_SQL, FIELDS);
    }
}

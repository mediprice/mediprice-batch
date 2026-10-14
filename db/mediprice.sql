-- =====================================================================
-- MediPrice - HIRA 공공데이터 동기화 스키마 (PostgreSQL / Neon)
--
-- 테이블 8개
--   코드     : region_sido, region_sggu, institution_class
--   병원     : hospital, hospital_detail
--   비급여   : npay_item, hospital_npay_price, npay_price_history
--
-- 설계 원칙
--  1. 조회·필터에 쓰는 값만 컬럼으로 만들고, API 응답 원본은 raw(jsonb)에 통째로 보관한다.
--  2. 컬럼명은 API 필드명을 snake_case로 바꾼 이름을 쓴다. (curAmt -> cur_amt)
--  3. 코드값(시도·종별·우편번호 등)은 전부 문자열로 저장한다. ("01"의 앞자리 0 유실 방지)
--  4. 동기화 테이블은 synced_at(마지막 저장 시각)을 가진다. 배치는 upsert 후
--     이번 실행에서 갱신되지 않은 행(synced_at < 배치 시작 시각)을 삭제한다.
--     단, npay_price_history는 삭제하지 않는다.
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS postgis;   -- 지도 반경 검색, 거리 계산


-- =====================================================================
-- 1. 코드 테이블 (병원코드정보서비스 codeInfoService)
-- =====================================================================

-- 시도 코드 (getAddrCodeList addrTp=1, 16건)
-- 광주는 별도 코드 없이 360000 '전남광주'에 포함된다.
CREATE TABLE region_sido (
    sido_cd     varchar(6)  PRIMARY KEY,           -- 시도코드 (예: 110000)
    sido_cd_nm  text        NOT NULL,              -- 시도명 (예: 서울)
    synced_at   timestamptz NOT NULL DEFAULT now() -- 마지막 저장 시각
);

-- 시군구 코드 (getAddrCodeList addrTp=2&sidoCd=..., 시도별로 호출)
CREATE TABLE region_sggu (
    sggu_cd     varchar(6)  PRIMARY KEY,           -- 시군구코드 (예: 110016)
    sido_cd     varchar(6)  NOT NULL
                REFERENCES region_sido (sido_cd),  -- 소속 시도 (예: 110000)
    sggu_cd_nm  text        NOT NULL,              -- 시군구명 (예: 종로구)
    synced_at   timestamptz NOT NULL DEFAULT now()
);

-- 종별 코드 (getMedicInsttClassesCodeList, 19건)
-- 코드표에 없는 29(정신병원)가 실제 병원·가격 데이터에 있다.
CREATE TABLE institution_class (
    cl_cd      varchar(2)  PRIMARY KEY,            -- 종별코드 (예: 01)
    cl_cd_nm   text        NOT NULL,               -- 종별명 (예: 상급종합병원)
    synced_at  timestamptz NOT NULL DEFAULT now()
);


-- =====================================================================
-- 2. 병원
-- =====================================================================

-- 병원 기본정보 (병원정보서비스 hospInfoServicev2/getHospBasisList, 79,851건 - 2026-10-07 기준)
-- sido_cd, cl_cd에는 FK를 걸지 않는다. 코드표에 없는 값이 실제로 온다 (광주 240000, 정신병원 29).
CREATE TABLE hospital (
    ykiho       text PRIMARY KEY,                  -- 병원 고유키 (병원정보·비급여·상세정보 API 공통)
    yadm_nm     text NOT NULL,                     -- 병원명 (예: 강북삼성병원)
    cl_cd       varchar(2),                        -- 종별코드 (예: 01)
    cl_cd_nm    text,                              -- 종별명 (예: 상급종합)
    sido_cd     varchar(6),                        -- 시도코드 (예: 110000)
    sido_cd_nm  text,                              -- 시도명 (예: 서울)
    sggu_cd     varchar(6),                        -- 시군구코드 (예: 110016)
    sggu_cd_nm  text,                              -- 시군구명 (예: 종로구)
    emdong_nm   text,                              -- 읍면동 (예: 평동)
    post_no     varchar(10),                       -- 우편번호 (예: 03181)
    addr        text,                              -- 주소
    telno       text,                              -- 대표 전화번호
    hosp_url    text,                              -- 홈페이지 (약 25%만 있음)
    estb_dd     date,                              -- 개설일자 (API "19790324" -> date)
    dr_tot_cnt  integer,                           -- 의사 총수 (과별 인원수는 raw)
    x_pos       double precision,                  -- 경도 (API 필드 XPos)
    y_pos       double precision,                  -- 위도 (API 필드 YPos)
    geog        geography(Point, 4326) GENERATED ALWAYS AS (
                    CASE WHEN x_pos IS NOT NULL AND y_pos IS NOT NULL
                         THEN ST_SetSRID(ST_MakePoint(x_pos, y_pos), 4326)::geography
                    END
                ) STORED,                          -- 위치 (x_pos, y_pos로 자동 계산. 배치가 넣지 않음)
    raw         jsonb,                             -- API 응답 원본
    synced_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_hospital_region ON hospital (sido_cd, sggu_cd);  -- 지역 필터
CREATE INDEX idx_hospital_cl_cd  ON hospital (cl_cd);             -- 종별 필터
CREATE INDEX idx_hospital_geog   ON hospital USING gist (geog);   -- 지도 반경 검색, 거리순 정렬

-- 병원 상세: 진료시간·주차·응급실 (의료기관별상세정보서비스 getDtlInfo2.8)
-- 대상: 가격 데이터가 있는 병원 (4,002곳 - 2026-10-07 기준). 병원마다 1번씩 호출한다.
-- detail 안의 주요 키
--   trmt{Mon|Tue|Wed|Thu|Fri|Sat|Sun}{Start|End} : 요일별 진료 시작/종료 (HHMM, 예: 0830)
--   rcvWeek, rcvSat     : 접수시간 (형식 제각각, 예: 08:00 ~ 17:00)
--   lunchWeek, lunchSat : 점심시간
--   noTrmtSun, noTrmtHoli : 일요일·공휴일 휴진 안내
--   parkQty, parkXpnsYn, parkEtc : 주차 대수, 주차비 부담 여부, 주차 안내
--   emyDayYn, emyNgtYn, emyDayTelNo1, emyNgtTelNo1 : 주간·야간 응급실 운영 여부, 전화번호
--   plcNm, plcDir, plcDist : 주변 공공장소 이름·방향·거리
CREATE TABLE hospital_detail (
    ykiho      text PRIMARY KEY
               REFERENCES hospital (ykiho) ON DELETE CASCADE,  -- 병원이 삭제되면 같이 삭제
    detail     jsonb,                              -- 세부정보 API 응답 원본 (데이터 없는 병원은 NULL)
    synced_at  timestamptz NOT NULL DEFAULT now()
);


-- =====================================================================
-- 3. 비급여
-- =====================================================================

-- 비급여 항목 (비급여 3번 getNonPaymentItemCodeList2, 943건 - 2026-10-07 기준)
-- 분류: 중분류 > 소분류 > 상세분류 (예: 1070Z 이학요법료 > 1070Z050 도수치료)
-- '1991C 예방접종료'처럼 분류 묶음 역할을 하는 코드는 세 분류가 모두 자기 자신이고 가격 데이터가 없다.
CREATE TABLE npay_item (
    npay_cd             varchar(20) PRIMARY KEY,   -- 비급여코드 (예: MX1220000)
    npay_kor_nm         text NOT NULL,             -- 항목명 (예: 이학요법료/도수치료)
    npay_mdiv_cd        varchar(10),               -- 중분류코드 (예: 1070Z)
    npay_mdiv_cd_nm     text,                      -- 중분류명 (예: 이학요법료)
    npay_sdiv_cd        varchar(10),               -- 소분류코드 (예: 1070Z050)
    npay_sdiv_cd_nm     text,                      -- 소분류명 (예: 도수치료)
    npay_dtl_div_cd     varchar(10),               -- 상세분류코드
    npay_dtl_div_cd_nm  text,                      -- 상세분류명
    cmmt_txt            text,                      -- 항목 설명 (항목에 따라 없음)
    adt_fr_dd           date,                      -- 적용 시작일
    adt_end_dd          date,                      -- 적용 종료일 (9999-12-31 = 현재 유효)
    raw                 jsonb,                     -- API 응답 원본
    synced_at           timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_npay_item_category
    ON npay_item (npay_mdiv_cd, npay_sdiv_cd, npay_dtl_div_cd);  -- 카테고리 탐색

-- 병원별 비급여 현재 가격 (비급여 5번 getNonPaymentItemHospDtlList, 279,285건 - 2026-10-07 기준)
-- 서비스 핵심 테이블. 매번 최신 가격으로 덮어쓴다.
-- 한 병원이 같은 항목을 여러 행(병원 자체 명칭별)으로 가질 수 있다.
--   예: 강북삼성병원 도수치료 3행 (44,000 / 86,000 / 59,000원)
-- PK (ykiho, sno): 전체 데이터에서 sno 누락 0건, 중복 0건 확인.
CREATE TABLE hospital_npay_price (
    ykiho            text        NOT NULL
                     REFERENCES hospital (ykiho) ON DELETE CASCADE,  -- 병원이 삭제되면 같이 삭제
    sno              integer     NOT NULL,         -- 일련번호 (병원 안에서 가격 행 구분)
    npay_cd          varchar(20) NOT NULL
                     REFERENCES npay_item (npay_cd),                 -- 비급여코드 (예: MX1220000)
    yadm_npay_cd_nm  text,                         -- 병원 자체 항목명 (예: 도수치료 [1일당])
    cur_amt          bigint      NOT NULL,         -- 현재금액 (원). 0원은 실제 무료 (증명서 수수료 등)
    adt_fr_dd        date,                         -- 병원이 이 가격을 적용하기 시작한 날
    adt_end_dd       date,                         -- 적용 종료일 (9999-12-31 = 현재 유효)
    raw              jsonb,                        -- API 응답 원본
    synced_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (ykiho, sno)
);

CREATE INDEX idx_price_item_amt ON hospital_npay_price (npay_cd, cur_amt);  -- 항목별 최저가 조회

-- 월별 가격 이력 (hospital_npay_price를 매달 복사, 한 달 약 28만 행 추가)
-- HIRA API는 현재 가격만 주므로, 가격 변동 추이를 보려면 지금부터 쌓아야 한다.
-- FK를 걸지 않는다: 병원이 폐업해 hospital에서 삭제돼도 과거 가격 기록은 남긴다.
-- 배치 정리 단계에서 삭제하지 않는다.
-- 같은 달에 배치가 여러 번 돌면 그 달 행을 최신 값으로 덮어쓴다.
-- 그래프는 (ykiho, npay_cd, yadm_npay_cd_nm)으로 묶어 월별로 잇는다.
CREATE TABLE npay_price_history (
    snapshot_month   date        NOT NULL          -- 기준 월 (매월 1일, 예: 2026-10-01)
                     CHECK (extract(day FROM snapshot_month) = 1),
    ykiho            text        NOT NULL,         -- 병원 고유키
    sno              integer     NOT NULL,         -- 그 달의 일련번호
    npay_cd          varchar(20) NOT NULL,         -- 비급여코드
    yadm_npay_cd_nm  text,                         -- 병원 자체 항목명
    cur_amt          bigint      NOT NULL,         -- 그 달의 금액 (원)
    synced_at        timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (snapshot_month, ykiho, sno)
);

CREATE INDEX idx_history_hospital_item
    ON npay_price_history (ykiho, npay_cd, snapshot_month);  -- 병원·항목별 가격 추이 그래프

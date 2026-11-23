-- =====================================================================
-- 012. 홈 화면 통계를 미리 계산해 두는 한 행 표 data_stats 추가
-- 적용 시점: 아무 때나 (새 표를 만들 뿐). 이 브랜치의 배치를 배포하기 **전**에 적용한다 (새 배치가 이 표에 씀)
-- 트랜잭션: 한 트랜잭션으로 실행 (psql -1 -f 또는 SQL Editor에 통째로)
--
-- 무엇을: data_stats(id 항상 1인 한 행: hospital_count, npay_item_count, price_count, data_date, refreshed_at)
-- 왜: 백엔드 /api/stats가 매번(10분 캐시 만료마다) 가격 테이블 전체를 훑어 세는 대신, 배치가 가격 동기화 뒤
--     한 번 세어 둔 값을 읽는다. 값의 정의는 지금 백엔드가 세는 값과 같다.
--     (hospital_count = 가격이 있는 병원 수, npay_item_count = 가격이 있는 비급여 항목 수, price_count = 가격 행 수)
--     data_date는 "hiraSyncJob의 가격 동기화가 마지막으로 성공한 날짜(한국 날짜)". 가격 테이블의 synced_at이
--     "마지막으로 값이 바뀐 시각"으로 의미가 바뀌어 max(synced_at)으로는 기준일을 알 수 없다.
-- 잠금 영향: 새 표라 기존 읽기·쓰기에 영향 없음
-- 적용 전 확인: SELECT to_regclass('data_stats');  -- NULL이어야 함
-- 이 파일은 빈 표만 만든다. 행은 배치가 hiraSyncJob을 한 번 끝까지 돌 때 처음 생긴다
--   (행이 없는 동안 백엔드는 예전처럼 가격 테이블에서 직접 센다).
-- 권한: 아래 DO 블록이 mediprice_batch / mediprice_app 롤이 있을 때만 GRANT한다 (롤이 없는 DB에서는 아무것도 안 함).
--   적용 순서: **012(GRANT 포함)는 백엔드 #23 배포 전에.** 표만 있고 GRANT가 없으면 앱 역할의 data_stats 조회가
--   permission denied로 실패해 /api/stats가 실패한다. 표 소유자(또는 GRANT 권한자)가 이 파일을 실행해야 GRANT가 적용된다.
-- 되돌리기 (배치를 이전 버전으로 되돌린 뒤): DROP TABLE data_stats;
-- =====================================================================

SET lock_timeout = '5s';

CREATE TABLE IF NOT EXISTS data_stats (
    id              smallint    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    hospital_count  integer     NOT NULL,
    npay_item_count integer     NOT NULL,
    price_count     integer     NOT NULL,
    data_date       date,
    refreshed_at    timestamptz NOT NULL DEFAULT now()
);

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mediprice_batch') THEN
        GRANT SELECT, INSERT, UPDATE, DELETE ON data_stats TO mediprice_batch;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'mediprice_app') THEN
        GRANT SELECT ON data_stats TO mediprice_app;
    END IF;
END $$;

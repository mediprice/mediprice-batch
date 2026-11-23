-- =====================================================================
-- 001. 바뀐 행만 쓰는 배치를 위한 작업 테이블과 컬럼 추가
-- 적용 시점: 이 브랜치의 배치를 배포하기 **전** (새 배치가 이 테이블·컬럼에 씀)
-- 트랜잭션: 한 트랜잭션으로 실행 (psql -1 -f 또는 SQL Editor에 통째로)
--
-- 무엇을
--   - hospital_seen, hospital_npay_price_seen: Writer가 이번 실행에 본 키를 적는 작업 테이블 (정리 Step이 성공하면 TRUNCATE)
--   - hospital.missed_since, hospital_npay_price.missed_since: 정리 Step이 이 행을 처음 못 본 실행의 기준 시각
-- 왜
--   배치가 값이 같은 행은 다시 쓰지 않게 바뀌어 synced_at으로는 "이번 실행에 본 행"을 알 수 없다.
--   본 키를 따로 적고, 두 번 연속 안 보인 행만 지운다 (지금 동작과 같은 유예 1회).
-- 잠금 영향
--   ADD COLUMN(기본값 없음, NULL 허용)은 카탈로그만 바꿔 즉시 끝나지만 ACCESS EXCLUSIVE를 잠깐 잡는다.
--   앞에 긴 쿼리가 있으면 그 뒤 SELECT까지 줄을 서므로 lock_timeout으로 5초 넘게 기다리면 실패하게 둔다 (다시 실행하면 됨).
--   배치가 돌지 않는 시간에 적용한다.
-- 적용 전 확인
--   SELECT to_regclass('hospital_seen'), to_regclass('hospital_npay_price_seen');           -- 둘 다 NULL이어야 함
--   SELECT table_name, column_name FROM information_schema.columns
--    WHERE column_name = 'missed_since' AND table_name IN ('hospital', 'hospital_npay_price'); -- 0행이어야 함
-- 되돌리기 (이전 배치로 되돌린 뒤)
--   DROP TABLE hospital_seen, hospital_npay_price_seen;
--   ALTER TABLE hospital DROP COLUMN missed_since;
--   ALTER TABLE hospital_npay_price DROP COLUMN missed_since;
-- =====================================================================

SET lock_timeout = '5s';

CREATE TABLE hospital_seen (
    ykiho  text NOT NULL
);

CREATE TABLE hospital_npay_price_seen (
    ykiho  text    NOT NULL,
    sno    integer NOT NULL
);

ALTER TABLE hospital            ADD COLUMN missed_since timestamptz;
ALTER TABLE hospital_npay_price ADD COLUMN missed_since timestamptz;

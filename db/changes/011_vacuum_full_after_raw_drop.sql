-- =====================================================================
-- 011. (점검 시간) raw를 지운 테이블의 파일 크기 실제로 줄이기
-- 적용 시점: 010 뒤, 서비스 점검 시간. 선택 사항이지만 하지 않으면 010의 공간이 거의 돌아오지 않는다.
-- 트랜잭션: **트랜잭션 밖에서** 실행 (VACUUM은 트랜잭션 블록 안에서 실패함). psql -f 로 실행하고 -1을 쓰지 않는다.
--           테이블마다 문장을 나눴다: 하나가 잠금을 못 얻어 실패해도 앞 테이블 작업은 남고, 실패한 것만 다시 실행하면 된다.
-- 접속: Neon **pooler가 아닌 direct 주소**로 적용한다 (pooler(transaction mode)는 SET이 다음 문장까지 이어진다는 보장이 없고,
--       오래 걸리는 문장이 pooler 제한 시간에 끊길 수 있음).
--
-- 왜: 새 배치는 값이 바뀐 행만 다시 쓰므로 지운 raw 값이 그대로 남는다. VACUUM FULL이 테이블을 새로 써서 줄인다
--     (감사 추정: 가격 heap 210 MB → 약 55 MB, 병원 89 MB → 약 30 MB. 로컬 샘플 실측 219 → 47 MB, 89 → 22 MB).
-- 잠금 영향: **ACCESS EXCLUSIVE — 끝날 때까지 그 테이블 읽기도 막힌다** (가격 210 MB 기준 수 초~수십 초 추정).
--   lock_timeout 5초: 앞에 긴 쿼리가 있으면 기다리는 동안 뒤 SELECT까지 줄을 서므로, 5초 안에 잠금을 못 얻으면 그 문장만 실패시킨다.
--   **잠금 없이 하려면 pg_repack**(Neon 지원 확장)을 쓴다. 작업 중 테이블 크기만큼 디스크가 더 필요하다.
-- 적용 전 확인: SELECT relname, pg_size_pretty(pg_table_size(oid)) FROM pg_class
--               WHERE relname IN ('hospital_npay_price', 'hospital', 'npay_item');
-- 되돌리기: 없음 (데이터는 그대로이고 파일만 새로 씀)
-- =====================================================================

SET lock_timeout = '5s';

VACUUM (FULL, ANALYZE) hospital_npay_price;
VACUUM (FULL, ANALYZE) hospital;
VACUUM (FULL, ANALYZE) npay_item;

-- =====================================================================
-- 005. 예전 가격 인덱스 (npay_cd, cur_amt) 제거
-- 적용 시점: 004가 끝나고 EXPLAIN으로 새 인덱스를 쓰는 것을 확인한 **뒤**
-- 트랜잭션: **트랜잭션 밖에서** 실행 (DROP INDEX CONCURRENTLY. 이 파일엔 이 문장 하나만 둠)
--
-- 왜: idx_price_item_cover의 앞 두 컬럼이 같아 이 인덱스로 하던 일을 모두 대신한다
--     (FK npay_cd 삭제 검사, 정리 Step의 NOT EXISTS (p.npay_cd = ...) 포함). 남기면 가격이 바뀔 때마다 인덱스를 둘 다 고친다.
-- 잠금 영향: CONCURRENTLY라 읽기·쓰기를 막지 않음.
-- 적용 전 확인 (true여야 함):
--   SELECT indisvalid FROM pg_index WHERE indexrelid = 'idx_price_item_cover'::regclass;
-- 되돌리기: CREATE INDEX CONCURRENTLY idx_price_item_amt ON hospital_npay_price (npay_cd, cur_amt);
-- =====================================================================

DROP INDEX CONCURRENTLY IF EXISTS idx_price_item_amt;

-- =====================================================================
-- 004. 가격 커버링 인덱스 (npay_cd, cur_amt, ykiho, sno)
-- 적용 시점: 배포와 무관. 005보다 먼저
-- 트랜잭션: **트랜잭션 밖에서** 실행 (CONCURRENTLY. 이 파일엔 이 문장 하나만 둠)
--
-- 왜: 항목 가격 요약·목록·근처 최저가가 모두 idx_price_item_amt (npay_cd, cur_amt)로 찾은 뒤 heap을 읽는다
--     (dev 실측 MX1220000: 버퍼 3,608, 근처+항목 7,217). ykiho, sno까지 넣으면
--     요약·근처 최저가는 index-only scan, 목록(ORDER BY cur_amt, ykiho, sno LIMIT)은 정렬 없이 앞에서 멈춘다.
--     index-only 효과는 visibility map이 채워져야 나므로 배치 끝 VACUUM Step과 함께 쓴다.
-- 비용: 인덱스 약 5 MB → 약 35 MB (ykiho가 80자라서, 감사 추정)
-- 잠금 영향: 읽기·쓰기를 막지 않음. 배치가 가격을 쓰는 중이면 그 트랜잭션이 끝날 때까지 기다리므로 배치가 안 도는 시간에.
-- 적용 후 확인: SELECT indisvalid FROM pg_index WHERE indexrelid = 'idx_price_item_cover'::regclass;  -- true
--             EXPLAIN SELECT min(cur_amt), count(DISTINCT ykiho) FROM hospital_npay_price WHERE npay_cd = 'MX1220000';
--               -- Index Only Scan using idx_price_item_cover
-- 실패하면: DROP INDEX CONCURRENTLY IF EXISTS idx_price_item_cover; 후 다시 실행
-- 되돌리기: DROP INDEX CONCURRENTLY IF EXISTS idx_price_item_cover;  (005를 적용했다면 idx_price_item_amt를 먼저 다시 만든다)
-- =====================================================================

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_price_item_cover ON hospital_npay_price (npay_cd, cur_amt, ykiho, sno);

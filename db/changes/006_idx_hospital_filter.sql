-- =====================================================================
-- 006. 병원 지역·종별 필터 인덱스 (sido_cd, sggu_cd, cl_cd) INCLUDE (ykiho)
-- 적용 시점: 배포와 무관. 007보다 먼저
-- 트랜잭션: **트랜잭션 밖에서** 실행 (CONCURRENTLY. 이 파일엔 이 문장 하나만 둠)
--
-- 왜: 항목 가격 목록에 지역 필터를 걸면 병원 쪽을 Bitmap Heap Scan으로 8,100페이지 읽었다
--     (dev 실측 서울 19,920행, count 냉 255 ms). 해시 조인의 병원 쪽이 쓰는 값(sido_cd, sggu_cd, cl_cd, ykiho)을
--     모두 인덱스에 넣어 heap을 읽지 않게(index-only) 한다.
-- 쓰는 쿼리 (backend·AI 코드 확인, 2026-11-23)
--   - backend HospitalSpecs / HospitalNpayPriceSpecs: sido_cd IN (...), sggu_cd =, cl_cd = 의 조합
--   - AI app/tools/prices.py: h.sido_cd =, h.sggu_cd = ; app/tools/regions.py: sido_cd = + 반경(geog)
-- 잠금 영향: 읽기·쓰기를 막지 않음.
-- 적용 후 확인: SELECT indisvalid FROM pg_index WHERE indexrelid = 'idx_hospital_filter'::regclass;  -- true
-- 실패하면: DROP INDEX CONCURRENTLY IF EXISTS idx_hospital_filter; 후 다시 실행
-- 되돌리기: DROP INDEX CONCURRENTLY IF EXISTS idx_hospital_filter;  (007을 적용했다면 idx_hospital_region을 먼저 다시 만든다)
-- =====================================================================

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_hospital_filter ON hospital (sido_cd, sggu_cd, cl_cd) INCLUDE (ykiho);

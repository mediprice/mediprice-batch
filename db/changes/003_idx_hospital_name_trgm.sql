-- =====================================================================
-- 003. 병원명 부분 검색 인덱스
-- 적용 시점: 배포와 무관. 002 다음
-- 트랜잭션: **트랜잭션 밖에서** 실행 (CREATE INDEX CONCURRENTLY는 트랜잭션 블록 안에서 실패함. 그래서 이 파일엔 이 문장 하나만 둠)
--           psql -f 로 실행하고 -1/--single-transaction을 쓰지 않는다. Neon SQL Editor는 문장 하나면 그대로 된다.
--
-- 무엇을: hospital.yadm_nm에 trigram GIN 인덱스
-- 왜: backend 병원 검색(yadm_nm ILIKE '%검색어%')이 매 요청 8만 행을 전체 스캔했다(dev 실측 11,445페이지, 35~118 ms, 목록+count 두 번).
--     3글자 이상 검색어는 이 인덱스로 바로 찾는다.
--     **2글자 한국어 검색어('%삼성%')는 trigram(3글자 묶음)을 만들 수 없어 인덱스를 못 탄다** → 여전히 전체 스캔(감사 때 실측).
--     2글자 검색이 많으면 pg_bigm 같은 2글자 방식이나 목록 count 생략(Slice)을 따로 검토한다.
-- 잠금 영향: CONCURRENTLY라 읽기·쓰기를 막지 않는다 (SHARE UPDATE EXCLUSIVE). 수 초~수십 초.
-- 적용 전 확인: SELECT * FROM pg_extension WHERE extname = 'pg_trgm';  -- 1행 (002 적용됨)
-- 적용 후 확인: SELECT indisvalid FROM pg_index WHERE indexrelid = 'idx_hospital_name_trgm'::regclass;  -- true
--             EXPLAIN SELECT ykiho FROM hospital WHERE yadm_nm ILIKE '%삼성병원%';  -- Bitmap Index Scan on idx_hospital_name_trgm
-- 실패하면: INVALID 인덱스가 남으므로 DROP INDEX CONCURRENTLY IF EXISTS idx_hospital_name_trgm; 후 다시 실행
-- 되돌리기: DROP INDEX CONCURRENTLY IF EXISTS idx_hospital_name_trgm;
-- =====================================================================

CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_hospital_name_trgm ON hospital USING gin (yadm_nm gin_trgm_ops);

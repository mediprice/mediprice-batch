-- =====================================================================
-- 007. 예전 병원 지역 인덱스 (sido_cd, sggu_cd) 제거
-- 적용 시점: 006이 끝난 **뒤**
-- 트랜잭션: **트랜잭션 밖에서** 실행 (DROP INDEX CONCURRENTLY. 이 파일엔 이 문장 하나만 둠)
--
-- 왜: idx_hospital_filter의 앞 두 컬럼과 같아 시도만/시도+시군구 조건을 모두 대신한다.
-- idx_hospital_cl_cd (cl_cd)는 **남긴다**:
--   - 시도 없이 종별만 거는 쿼리가 있다: backend HospitalSpecs(institutionCode만), HospitalNpayPriceSpecs(clCd만),
--     NearbyHospitalQuery(" and h.cl_cd = :institutionCode", 반경 조건과 함께).
--   - (sido_cd, sggu_cd, cl_cd)로 cl_cd만 찾으려면 skip scan이 필요한데 PostgreSQL 18부터 있다.
--     dev는 18.7이지만 운영 버전이 확인되지 않았고, 17 이하에서는 전체 스캔이 된다(상급종합 '01'은 47곳뿐이라 차이가 큼).
--   - 크기 약 2 MB이고, 병원 행은 이제 값이 바뀔 때만 다시 써서 유지 비용이 거의 없다.
-- 잠금 영향: CONCURRENTLY라 읽기·쓰기를 막지 않음.
-- 적용 전 확인 (true여야 함):
--   SELECT indisvalid FROM pg_index WHERE indexrelid = 'idx_hospital_filter'::regclass;
-- 되돌리기: CREATE INDEX CONCURRENTLY idx_hospital_region ON hospital (sido_cd, sggu_cd);
-- =====================================================================

DROP INDEX CONCURRENTLY IF EXISTS idx_hospital_region;

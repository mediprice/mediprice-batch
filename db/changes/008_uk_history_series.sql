-- =====================================================================
-- 008. 이력 PK로 쓸 유니크 인덱스 (ykiho, npay_cd, snapshot_month, sno) 미리 만들기
-- 적용 시점: 배포와 무관 (새 배치의 이력 SQL은 예전·새 PK 어느 쪽에서도 동작함). 009보다 먼저
-- 트랜잭션: **트랜잭션 밖에서** 실행 (CONCURRENTLY. 이 파일엔 이 문장 하나만 둠)
--
-- 왜: 이력 테이블에 인덱스가 둘이다. PK (snapshot_month, ykiho, sno)는 조회에 안 쓰이고(dev idx_scan=0, 56 MB),
--     조회는 idx_history_hospital_item (ykiho, npay_cd, snapshot_month)만 쓴다. 둘을 이 인덱스 하나로 합치면
--     한 달 스냅샷 인덱스가 약 90 MB → 약 35 MB로 준다(감사 추정). 009에서 이 인덱스를 PK로 바꾼다.
-- 유일성: 예전 PK (snapshot_month, ykiho, sno)가 유일하면 컬럼이 하나 더 많은 이 키도 반드시 유일하므로 실패하지 않는다.
-- 잠금 영향: 읽기·쓰기를 막지 않음. 이력 행 수에 비례해 시간이 걸린다 (dev 28만 행 기준 수 초~수십 초 추정).
--           priceHistoryStep이 도는 중이면 끝날 때까지 기다리므로 배치가 안 도는 시간에.
-- 적용 후 확인: SELECT indisvalid FROM pg_index WHERE indexrelid = 'uk_history_series'::regclass;  -- true
-- 실패하면: DROP INDEX CONCURRENTLY IF EXISTS uk_history_series; 후 다시 실행
-- 되돌리기: DROP INDEX CONCURRENTLY IF EXISTS uk_history_series;
-- =====================================================================

CREATE UNIQUE INDEX CONCURRENTLY IF NOT EXISTS uk_history_series
    ON npay_price_history (ykiho, npay_cd, snapshot_month, sno);

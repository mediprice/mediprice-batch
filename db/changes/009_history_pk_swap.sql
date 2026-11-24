-- =====================================================================
-- 009. 이력 PK를 (ykiho, npay_cd, snapshot_month, sno)로 바꾸고 조회 인덱스 제거
-- 적용 시점: 008이 끝난 **뒤**. 배포와 무관
-- 트랜잭션: 한 트랜잭션으로 실행 (psql -1 -f 또는 SQL Editor에 통째로). 중간에 실패하면 전부 되돌아간다.
--
-- 무엇을: 예전 PK 제약을 지우고 008의 유니크 인덱스를 PK로 붙인 뒤(이름이 npay_price_history_pkey로 바뀜),
--         PK 앞부분과 같은 idx_history_hospital_item을 지운다.
-- 왜: 008 참고. backend 가격 추이 조회(ykiho =, npay_cd =, snapshot_month between)는 새 PK 앞부분을 쓴다.
-- 잠금 영향: 인덱스를 새로 만들지 않고 카탈로그만 바꿔 바로 끝나지만 ACCESS EXCLUSIVE를 잡는다.
--           lock_timeout 5초. 배치가 안 도는 시간에.
-- 적용 전 확인 (true여야 함):
--   SELECT indisvalid FROM pg_index WHERE indexrelid = 'uk_history_series'::regclass;
-- 적용 후 확인:
--   SELECT pg_get_indexdef('npay_price_history_pkey'::regclass);  -- (ykiho, npay_cd, snapshot_month, sno)
--   EXPLAIN SELECT * FROM npay_price_history WHERE ykiho = '...' AND npay_cd = '...'
--     AND snapshot_month BETWEEN '2026-01-01' AND '2026-12-01';  -- npay_price_history_pkey 사용
-- 되돌리기 (한 트랜잭션):
--   ALTER TABLE npay_price_history DROP CONSTRAINT npay_price_history_pkey;
--   ALTER TABLE npay_price_history ADD PRIMARY KEY (snapshot_month, ykiho, sno);
--   CREATE INDEX idx_history_hospital_item ON npay_price_history (ykiho, npay_cd, snapshot_month);
-- =====================================================================

SET lock_timeout = '5s';

ALTER TABLE npay_price_history DROP CONSTRAINT npay_price_history_pkey;
ALTER TABLE npay_price_history ADD CONSTRAINT npay_price_history_pkey PRIMARY KEY USING INDEX uk_history_series;
DROP INDEX IF EXISTS idx_history_hospital_item;

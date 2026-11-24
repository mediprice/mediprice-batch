-- =====================================================================
-- 010. 원본 raw(jsonb) 컬럼 제거
-- 적용 시점: 이 브랜치의 배치를 **배포한 뒤** (예전 배치는 raw에 써서, 먼저 지우면 예전 배치가 실패함)
-- 트랜잭션: 한 트랜잭션으로 실행 (psql -1 -f 또는 SQL Editor에 통째로)
--
-- 무엇을: hospital_npay_price.raw, hospital.raw, npay_item.raw 삭제
-- 왜: backend·AI·frontend 어디에서도 읽지 않는다 (2026-11-23 각 저장소 모든 원격 브랜치 git grep 확인).
--     가격 raw에서 컬럼에 없던 값은 urlAddr(병원 홈페이지) 하나였고, 읽는 곳이 없어 따로 컬럼으로 두지 않는다.
--     병원 raw의 과별 인원수(mdept*, cmdc*, dety*, pnursCnt)도 읽는 곳이 없다. npay_item raw는 943행이라 작지만 같은 이유로 지운다.
--     raw가 가격 행의 75%(570 B / 755 B), 병원 행의 68%(765 B / 1,122 B)였다(dev 실측).
-- 잠금 영향: DROP COLUMN은 카탈로그만 바꿔 바로 끝나지만 ACCESS EXCLUSIVE를 잡는다. lock_timeout 5초. 배치가 안 도는 시간에.
-- **디스크는 바로 줄지 않는다**: 지운 컬럼 값은 행이 다시 쓰일 때까지 남는데, 새 배치는 값이 바뀐 행만 다시 쓰므로
--   거의 그대로 남는다. 실제로 줄이려면 점검 시간에 011을 실행한다.
-- 적용 전 확인 (새 배치가 한 번 이상 끝까지 돌았는지. 새 배치에만 있는 마지막 Step):
--   SELECT max(end_time) FROM batch_step_execution WHERE step_name = 'vacuumStep' AND status = 'COMPLETED';  -- NULL이 아니어야 함
--   그리고 예전 버전 배치가 다시 실행될 일이 없는지(자동 실행 설정 등) 확인한다.
-- 되돌리기: 컬럼은 다시 만들 수 있지만(ALTER TABLE ... ADD COLUMN raw jsonb) 지운 값은 돌아오지 않는다.
--   필요하면 적용 전에 pg_dump로 백업한다.
-- =====================================================================

SET lock_timeout = '5s';

ALTER TABLE hospital_npay_price DROP COLUMN IF EXISTS raw;
ALTER TABLE hospital            DROP COLUMN IF EXISTS raw;
ALTER TABLE npay_item           DROP COLUMN IF EXISTS raw;

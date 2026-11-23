-- =====================================================================
-- 002. pg_trgm 확장 (003의 병원명 trigram 인덱스에 필요)
-- 적용 시점: 배포와 무관. 003보다 먼저
-- 트랜잭션: 상관없음 (문장 하나)
--
-- 왜: dev에는 AI 스크립트(mediprice-ai db/migrations/ai_001_search.sql)가 이미 설치했지만,
--     새로 만든 DB나 AI 스크립트를 안 돌린 DB에는 없을 수 있어 IF NOT EXISTS로 둔다.
-- 잠금 영향: 없음 (확장 객체만 만듦)
-- 적용 전 확인: SELECT extversion FROM pg_extension WHERE extname = 'pg_trgm';  -- 있으면 이 파일은 아무 일도 안 함
-- 되돌리기: 하지 않는다 (npay_item의 AI trigram 인덱스도 이 확장을 씀)
-- =====================================================================

CREATE EXTENSION IF NOT EXISTS pg_trgm;

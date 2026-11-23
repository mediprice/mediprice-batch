-- =====================================================================
-- 013. hospital_detail에 사이트맵용 price_lastmod 컬럼 추가
-- 적용 시점: 이 브랜치의 배치를 배포하기 **전** (새 배치가 이 컬럼에 씀). 012와 순서 무관
-- 트랜잭션: 한 트랜잭션으로 실행 (psql -1 -f 또는 SQL Editor에 통째로)
--
-- 무엇을: hospital_detail.price_lastmod date = 그 병원 가격 중 가장 최근 적용 시작일(adt_fr_dd).
--         오늘(한국 날짜) 이후 시작일은 뺀다. 가격이 없거나 시작일이 모두 비었거나 모두 미래면 NULL.
-- 왜: 백엔드 사이트맵(/api/hospitals/sitemap)이 가격 28만 행을 병원별로 group by 하는 대신 병원 4천 행을 읽는다.
--     뜻은 지금 백엔드(HospitalNpayPriceRepository.findSitemapEntries)와 같다:
--     max(adt_fr_dd) FILTER (WHERE adt_fr_dd <= 오늘). 미래 날짜 lastmod는 검색엔진이 무시하므로 뺀다.
--     이후에는 배치가 상세 단계 뒤에 매번 채운다 (바뀐 행만 씀).
-- 잠금 영향: ADD COLUMN(기본값 없음)은 카탈로그만 바꿔 즉시 끝나지만 ACCESS EXCLUSIVE를 잠깐 잡는다.
--   lock_timeout 5초로 두어 오래 줄 서지 않게 한다 (실패하면 다시 실행). 아래 UPDATE는 약 4천 행.
-- 적용 전 확인: SELECT column_name FROM information_schema.columns
--               WHERE table_name = 'hospital_detail' AND column_name = 'price_lastmod';  -- 0행이어야 함
-- 되돌리기: ALTER TABLE hospital_detail DROP COLUMN price_lastmod;
-- =====================================================================

SET lock_timeout = '5s';

ALTER TABLE hospital_detail ADD COLUMN IF NOT EXISTS price_lastmod date;

-- 지금 값으로 채움 (배치가 다음에 돌 때까지 기다리지 않도록). 기준일은 한국 날짜
UPDATE hospital_detail d
SET price_lastmod = m.lastmod
FROM (SELECT hd.ykiho,
             (SELECT max(p.adt_fr_dd) FROM hospital_npay_price p
               WHERE p.ykiho = hd.ykiho AND p.adt_fr_dd <= (now() AT TIME ZONE 'Asia/Seoul')::date) AS lastmod
      FROM hospital_detail hd) m
WHERE d.ykiho = m.ykiho AND d.price_lastmod IS DISTINCT FROM m.lastmod;

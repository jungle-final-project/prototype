-- 견적 도메인 감사 P0 백필 (docs/quote-domain-audit.md, 2026-07-06 로컬 DB 실측 기반).
-- 인테이크 휴리스틱 버그가 남긴 오염 값과 검사 신설에 필요한 결측 키를 정정한다.
-- 대상 행은 category + name 앵커로 고정한다(V94 관례). 행이 없으면 각 UPDATE는 0건으로 무해하다.

-- 1) CPU tdpW 결측 3행(INACTIVE seed) — 값은 이미 wattage 키에 정확히 있어 키 복사로 충분하다.
--    (Ryzen 5 7600=65W, Ryzen 7 7800X3D=120W, Core i5-14600KF=125W — 실제 TDP와 일치 확인)
UPDATE parts
SET attributes = attributes || jsonb_build_object('tdpW', (attributes->>'wattage')::int)
WHERE category = 'CPU'
  AND attributes ? 'wattage'
  AND NOT (attributes ? 'tdpW');

-- 2) NAVER 인테이크 moduleCount 상수 버그('? 2 : 2')가 단품에 킷(2)을 스탬프한 2행 — 키를 제거한다.
--    소비처는 moduleCount 미존재를 단품(1)로 계산한다. 나머지 NAVER RAM 10행은 실제 2개들이 킷이라 유지.
UPDATE parts
SET attributes = attributes - 'moduleCount'
WHERE category = 'RAM'
  AND name IN (
    '지스킬 DDR5-6000 CL36 AEGIS 5 32GB, 1개',
    '삼성전자 서버용 메모리 DDR5 32GB 6400 PC5 51200 ECC REG'
  );

-- 3) STORAGE 용량 이진 규칙(4TB/1TB/else 2000)이 남긴 오기재 3행 — 제목 기준 실값으로 정정한다.
UPDATE parts
SET attributes = jsonb_set(coalesce(attributes, '{}'::jsonb), '{capacityGb}', '8000'::jsonb, true)
WHERE category = 'STORAGE' AND name = '킹스톤 FURY RENEGADE G5 M.2 NVMe 8TB';

UPDATE parts
SET attributes = jsonb_set(coalesce(attributes, '{}'::jsonb), '{capacityGb}', '1000'::jsonb, true)
WHERE category = 'STORAGE'
  AND name IN (
    '샌디스크 WD BLACK SN8100 M.2 NVMe SSD 1TB',
    'SK하이닉스 Platinum P51 M.2 NVMe 1TB'
  );

-- 4) PSU depthMm 모순 중복행 — NAVER 하드코딩 기본값 160을 공식 스펙 125로 정정한다.
--    (동일 sourceProductKey의 OFFICIAL_MANUAL_SPEC 행과 SFX 규격 근거 — SFX-L도 130mm급이라 160은 불가)
UPDATE parts
SET attributes = jsonb_set(coalesce(attributes, '{}'::jsonb), '{depthMm}', '125'::jsonb, true)
WHERE category = 'PSU'
  AND name = '쿨러마스터 V SFX Gold 850 ATX3.1'
  AND attributes->>'depthMm' = '160';

-- 5) COOLER coolerType 어휘 정규화 — 'AIO' 1행(INACTIVE seed)을 표준 'LIQUID_AIO'로 통일한다.
--    (수랭 라디에이터 검사는 'LIQUID' 포함 여부로 판별하지만, 데이터 어휘도 하나로 맞춘다)
UPDATE parts
SET attributes = jsonb_set(coalesce(attributes, '{}'::jsonb), '{coolerType}', '"LIQUID_AIO"'::jsonb, true)
WHERE category = 'COOLER'
  AND attributes->>'coolerType' = 'AIO';

-- 감사 P1-1(a): 메인보드 폼팩터 백필 — ATX 오염 정정 + 표기 정규화.
-- 배경: ATX로 표기된 보드 중 이름에 mATX/ITX 칩셋 토큰(B850M, B860M-A, X870-I, Z890I 등)이 있는
-- 행이 15건 — 케이스 규격 검사(P1-1(b))가 오염된 값으로 "ITX 케이스에 ATX 보드 장착 불가" 같은
-- 오판정을 내리지 않도록 데이터부터 정리한다. (검증: 2026-07-06 감사 웹 검증 + 로컬 DB 실측)
-- 케이스의 formFactor는 'EATX_ATX_MATX_ITX' 같은 지원 목록 문자열이므로 여기서 건드리지 않는다.

-- 1) 칩셋 토큰 + I 접미(X870-I, Z890I 등) = Mini-ITX.
UPDATE parts
SET attributes = jsonb_set(coalesce(attributes, '{}'::jsonb), '{formFactor}', '"Mini-ITX"'::jsonb, true)
WHERE category = 'MOTHERBOARD'
  AND name ~* '[BXZH][0-9]{3}-?I([^0-9A-Za-z]|$)'
  AND upper(coalesce(replace(replace(attributes->>'formFactor', '-', ''), '_', ''), '')) NOT IN ('MINIITX', 'ITX');

-- 2) 칩셋 토큰 + M 접미(B850M, B860M-A 등) = M-ATX. (1번이 먼저라 ITX 보드와 겹치지 않는다)
UPDATE parts
SET attributes = jsonb_set(coalesce(attributes, '{}'::jsonb), '{formFactor}', '"M-ATX"'::jsonb, true)
WHERE category = 'MOTHERBOARD'
  AND name ~* '[BXZH][0-9]{3}M([^0-9A-Za-z]|$)'
  AND upper(coalesce(replace(replace(attributes->>'formFactor', '-', ''), '_', ''), '')) NOT IN ('MATX', 'MICROATX');

-- 3) 표기 정규화 — 'Micro-ATX'/'MATX'/'MINI_ITX'/'ITX' 등 변형을 표준 표기 하나로 통일한다.
--    (검사 로직은 변형도 이해하지만, 화면 노출·후속 백필의 기준 어휘를 고정해 둔다)
UPDATE parts
SET attributes = jsonb_set(attributes, '{formFactor}',
  CASE upper(replace(replace(replace(attributes->>'formFactor', '-', ''), '_', ''), ' ', ''))
    WHEN 'MICROATX' THEN '"M-ATX"'::jsonb
    WHEN 'MATX' THEN '"M-ATX"'::jsonb
    WHEN 'MINIITX' THEN '"Mini-ITX"'::jsonb
    WHEN 'ITX' THEN '"Mini-ITX"'::jsonb
    WHEN 'EATX' THEN '"E-ATX"'::jsonb
    WHEN 'EXTENDEDATX' THEN '"E-ATX"'::jsonb
    ELSE attributes->'formFactor'
  END, true)
WHERE category = 'MOTHERBOARD' AND attributes ? 'formFactor';

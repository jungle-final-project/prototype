-- 재스캔이 관리자/AI 검토 결과를 룰 기반 분류로 덮어쓰는 문제(감사 A1)를 막기 위한 분류 출처 컬럼.
-- RULE  : 스캔 시 룰 기반 자동 분류 (재스캔이 자유롭게 갱신 가능)
-- AI    : 관리자가 실행한 AI 구조화 결과 (재스캔이 분류를 덮어쓰지 않음)
-- ADMIN : 관리자가 직접 수정한 확정 상태 (재스캔이 분류를 덮어쓰지 않음)
ALTER TABLE manufacturer_posts
  ADD COLUMN classification_source text NOT NULL DEFAULT 'RULE';

ALTER TABLE manufacturer_posts
  ADD CONSTRAINT manufacturer_posts_classification_source_check
  CHECK (classification_source IN ('RULE', 'AI', 'ADMIN'));

-- 백필: raw_payload에 검토 이력이 남아 있는 기존 게시글은 해당 출처로 승격해 잠금 대상에 포함한다.
UPDATE manufacturer_posts
SET classification_source = 'ADMIN'
WHERE raw_payload ? 'adminReview';

UPDATE manufacturer_posts
SET classification_source = 'AI'
WHERE classification_source = 'RULE'
  AND raw_payload ? 'aiAssetDraft';

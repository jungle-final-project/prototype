-- 최신가 top-1 LATERAL(part_id = ? ORDER BY collected_at DESC, id DESC LIMIT 1)이
-- 인덱스에서 즉시 멈추도록 정렬키 전체를 담는다. 기존 (part_id, collected_at)은
-- id tiebreak가 없어 동일 collected_at 행에서 LIMIT 1 스톱이 안 된다.
-- 이 LATERAL은 parts 목록·홈 추천 후보 스캔에서 부품 행마다 실행되는 핫패스다.
CREATE INDEX IF NOT EXISTS idx_price_snapshots_part_latest
  ON price_snapshots (part_id, collected_at DESC, id DESC);

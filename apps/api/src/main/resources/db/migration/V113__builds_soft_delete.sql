-- 저장 견적 관리(삭제): 하드 삭제는 builds를 참조하는 학습/에이전트 테이블(recommendation_learning_pipeline,
-- agent_rag_tool, agent_log_summary feedback)의 FK를 건드리므로, 소프트 삭제로 사용자 목록에서만 감춘다.
-- 학습/분석 데이터의 build 참조는 그대로 보존된다.
ALTER TABLE builds ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMPTZ;

-- 사용자 견적함 목록/단건 조회가 deleted_at IS NULL로 자주 필터링하므로 부분 인덱스를 둔다.
CREATE INDEX IF NOT EXISTS idx_builds_active ON builds (requirement_id) WHERE deleted_at IS NULL;

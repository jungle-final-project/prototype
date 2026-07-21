ALTER TABLE quote_draft_history_entries
  ADD COLUMN evaluation_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  ADD COLUMN evaluation_score INTEGER,
  ADD COLUMN evaluation_max_score INTEGER,
  ADD COLUMN evaluation_issue_signature VARCHAR(64),
  ADD COLUMN evaluation_issue_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
  ADD COLUMN evaluation_started_at TIMESTAMPTZ,
  ADD COLUMN evaluated_at TIMESTAMPTZ,
  ADD CONSTRAINT chk_quote_draft_history_evaluation_status CHECK (
    evaluation_status IN ('PENDING', 'RUNNING', 'VALID', 'INVALID', 'UNAVAILABLE')
  ),
  ADD CONSTRAINT chk_quote_draft_history_evaluation_score CHECK (
    evaluation_score IS NULL OR evaluation_score >= 0
  );

CREATE INDEX idx_quote_draft_history_evaluation_pending
  ON quote_draft_history_entries(evaluation_status, created_at, id)
  WHERE evaluation_status IN ('PENDING', 'RUNNING');

CREATE INDEX idx_quote_draft_history_evaluation_recent
  ON quote_draft_history_entries(quote_draft_id, evaluation_status, created_at DESC, id DESC);

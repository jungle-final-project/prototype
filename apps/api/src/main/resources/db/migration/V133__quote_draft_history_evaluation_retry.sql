ALTER TABLE quote_draft_history_entries
  ADD COLUMN evaluation_attempts INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN evaluation_next_attempt_at TIMESTAMPTZ,
  ADD COLUMN evaluation_last_error_code VARCHAR(80),
  ADD COLUMN evaluation_last_error_at TIMESTAMPTZ,
  ADD CONSTRAINT chk_quote_draft_history_evaluation_attempts CHECK (evaluation_attempts >= 0);

DROP INDEX idx_quote_draft_history_evaluation_pending;

CREATE INDEX idx_quote_draft_history_evaluation_pending
  ON quote_draft_history_entries(evaluation_status, evaluation_next_attempt_at, created_at, id)
  WHERE evaluation_status IN ('PENDING', 'RUNNING');

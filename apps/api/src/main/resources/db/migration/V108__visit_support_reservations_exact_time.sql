ALTER TABLE visit_support_reservations
  ADD COLUMN IF NOT EXISTS scheduled_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_visit_support_reservations_scheduled_at
  ON visit_support_reservations(scheduled_at);

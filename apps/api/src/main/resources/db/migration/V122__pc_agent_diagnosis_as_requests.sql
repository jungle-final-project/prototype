CREATE SEQUENCE IF NOT EXISTS as_ticket_request_number_seq;

CREATE TABLE IF NOT EXISTS pc_agent_diagnosis_requests (
  diagnosis_id UUID PRIMARY KEY,
  user_id BIGINT NOT NULL REFERENCES users(id),
  agent_device_id BIGINT NOT NULL REFERENCES agent_devices(id),
  symptom TEXT NOT NULL,
  requested_checks JSONB NOT NULL CHECK (jsonb_typeof(requested_checks) = 'array'),
  requested_at TIMESTAMPTZ NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  mode VARCHAR(10) NOT NULL CHECK (mode IN ('LIVE', 'DEMO')),
  CHECK (expires_at > requested_at),
  CHECK (char_length(symptom) BETWEEN 1 AND 2000),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS ix_pc_agent_diagnosis_requests_device
  ON pc_agent_diagnosis_requests(agent_device_id, created_at DESC);

ALTER TABLE as_tickets
  ADD COLUMN IF NOT EXISTS diagnosis_id UUID,
  ADD COLUMN IF NOT EXISTS agent_device_id BIGINT REFERENCES agent_devices(id),
  ADD COLUMN IF NOT EXISTS request_number VARCHAR(40),
  ADD COLUMN IF NOT EXISTS request_type VARCHAR(50),
  ADD COLUMN IF NOT EXISTS diagnosis_title TEXT,
  ADD COLUMN IF NOT EXISTS diagnosis_summary TEXT,
  ADD COLUMN IF NOT EXISTS evidence_summary JSONB,
  ADD COLUMN IF NOT EXISTS diagnosed_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS diagnosis_mode VARCHAR(10),
  ADD COLUMN IF NOT EXISTS diagnosis_result JSONB,
  ADD COLUMN IF NOT EXISTS diagnosis_consent_accepted_at TIMESTAMPTZ;

CREATE UNIQUE INDEX IF NOT EXISTS ux_as_tickets_diagnosis_id
  ON as_tickets(diagnosis_id)
  WHERE diagnosis_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_as_tickets_request_number
  ON as_tickets(request_number)
  WHERE request_number IS NOT NULL;

CREATE INDEX IF NOT EXISTS ix_as_tickets_agent_device_id
  ON as_tickets(agent_device_id);

ALTER TABLE as_tickets
  DROP CONSTRAINT IF EXISTS chk_as_tickets_diagnosis_mode;

ALTER TABLE as_tickets
  ADD CONSTRAINT chk_as_tickets_diagnosis_mode CHECK (
    diagnosis_mode IS NULL OR diagnosis_mode IN ('LIVE', 'DEMO')
  );

ALTER TABLE as_tickets
  DROP CONSTRAINT IF EXISTS chk_as_tickets_request_type;

ALTER TABLE as_tickets
  ADD CONSTRAINT chk_as_tickets_request_type CHECK (
    request_type IS NULL OR request_type IN ('PHYSICAL_INSPECTION')
  );

ALTER TABLE remote_support_sessions
  ADD COLUMN IF NOT EXISTS access_code TEXT,
  ADD COLUMN IF NOT EXISTS access_code_registered_at TIMESTAMPTZ;

ALTER TABLE remote_support_sessions
  DROP CONSTRAINT IF EXISTS chk_remote_support_sessions_provider;

ALTER TABLE remote_support_sessions
  ADD CONSTRAINT chk_remote_support_sessions_provider CHECK (
    provider IN (
      'EXTERNAL_LINK',
      'CHROME_REMOTE_DESKTOP',
      'ANYDESK',
      'TEAMVIEWER',
      'ZOOM',
      'GOOGLE_MEET'
    )
  );

ALTER TABLE remote_support_sessions
  DROP CONSTRAINT IF EXISTS chk_remote_support_sessions_status;

ALTER TABLE remote_support_sessions
  ADD CONSTRAINT chk_remote_support_sessions_status CHECK (
    status IN (
      'REQUESTED',
      'LINK_SENT',
      'WAITING_FOR_CODE',
      'CODE_READY',
      'IN_PROGRESS',
      'COMPLETED',
      'CANCELLED'
    )
  );

COMMENT ON COLUMN remote_support_sessions.access_code IS
  'Ephemeral Chrome Remote Desktop support code. Clear when support is completed.';

COMMENT ON COLUMN remote_support_sessions.access_code_registered_at IS
  'Timestamp when the current one-time support code was registered or replaced.';

ALTER TABLE as_chat_sessions
  ALTER COLUMN as_ticket_id DROP NOT NULL;

ALTER TABLE as_chat_sessions
  DROP CONSTRAINT IF EXISTS chk_as_chat_sessions_status;

ALTER TABLE as_chat_sessions
  ADD CONSTRAINT chk_as_chat_sessions_status CHECK (
    status IN ('ACTIVE', 'ADMIN_REVIEWING', 'TICKET_CREATED', 'ARCHIVED')
  );

DROP INDEX IF EXISTS ux_as_chat_sessions_active_ticket_user;

CREATE UNIQUE INDEX IF NOT EXISTS ux_as_chat_sessions_active_ticket_user
  ON as_chat_sessions(user_id, as_ticket_id)
  WHERE as_ticket_id IS NOT NULL AND status = 'ACTIVE' AND deleted_at IS NULL;

ALTER TABLE as_chat_sessions
  ADD COLUMN IF NOT EXISTS assigned_admin_id BIGINT REFERENCES users(id),
  ADD COLUMN IF NOT EXISTS support_request_type VARCHAR(40) NOT NULL DEFAULT 'DIAGNOSIS_ONLY',
  ADD COLUMN IF NOT EXISTS last_message_preview TEXT,
  ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS admin_unread_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS user_unread_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS ticket_draft JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE as_chat_sessions
  DROP CONSTRAINT IF EXISTS chk_as_chat_sessions_support_request_type;

ALTER TABLE as_chat_sessions
  ADD CONSTRAINT chk_as_chat_sessions_support_request_type CHECK (
    support_request_type IN ('REMOTE', 'VISIT', 'DIAGNOSIS_ONLY')
  );

ALTER TABLE as_chat_sessions
  DROP CONSTRAINT IF EXISTS chk_as_chat_sessions_unread_counts;

ALTER TABLE as_chat_sessions
  ADD CONSTRAINT chk_as_chat_sessions_unread_counts CHECK (
    admin_unread_count >= 0 AND user_unread_count >= 0
  );

ALTER TABLE as_chat_messages
  DROP CONSTRAINT IF EXISTS chk_as_chat_messages_role;

ALTER TABLE as_chat_messages
  ADD CONSTRAINT chk_as_chat_messages_role CHECK (
    role IN ('USER', 'ADMIN', 'SYSTEM', 'ASSISTANT')
  );

ALTER TABLE as_chat_messages
  ADD COLUMN IF NOT EXISTS read_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS attachment_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE as_tickets
  ADD COLUMN IF NOT EXISTS source_chat_session_id BIGINT REFERENCES as_chat_sessions(id);

CREATE INDEX IF NOT EXISTS idx_as_chat_sessions_assigned_admin_id
  ON as_chat_sessions(assigned_admin_id);

CREATE INDEX IF NOT EXISTS idx_as_chat_sessions_last_message_at
  ON as_chat_sessions(last_message_at DESC);

CREATE INDEX IF NOT EXISTS idx_as_tickets_source_chat_session_id
  ON as_tickets(source_chat_session_id);

INSERT INTO as_chat_sessions (
  public_id,
  user_id,
  assigned_admin_id,
  status,
  title,
  support_request_type,
  last_message_preview,
  last_message_at,
  admin_unread_count,
  ticket_draft,
  created_at,
  updated_at
)
VALUES
  (
    '00000000-0000-4000-8000-000000064001',
    (SELECT id FROM users WHERE email = 'user@example.com'),
    (SELECT id FROM users WHERE email = 'admin@example.com'),
    'ACTIVE',
    '김민수 네트워크 상담',
    'REMOTE',
    '오늘 저녁 8시 이후 괜찮습니다.',
    '2026-07-03T22:48:00Z',
    2,
    '{"symptomType":"NETWORK_INTERNET","symptomSummary":"PC 부팅은 정상이나 인터넷 연결이 되지 않는 문제","preferredScheduleAt":"2026-07-03T20:00:00+09:00","adminNote":"원격으로 네트워크 설정과 어댑터 상태 확인 예정"}'::jsonb,
    '2026-07-03T22:45:00Z',
    '2026-07-03T22:48:00Z'
  ),
  (
    '00000000-0000-4000-8000-000000064002',
    (SELECT id FROM users WHERE email = 'user@example.com'),
    NULL,
    'ACTIVE',
    '박지영 프린터 상담',
    'VISIT',
    '프린터가 인쇄가 안 돼요.',
    '2026-07-03T22:35:00Z',
    1,
    '{"symptomType":"PERIPHERAL_PRINTER","symptomSummary":"프린터 인쇄 불가","preferredScheduleAt":"2026-07-04T14:00:00+09:00"}'::jsonb,
    '2026-07-03T22:33:00Z',
    '2026-07-03T22:35:00Z'
  )
ON CONFLICT (public_id) DO NOTHING;

INSERT INTO as_chat_messages (
  public_id,
  chat_session_id,
  role,
  content,
  created_at
)
VALUES
  ('00000000-0000-4000-8000-000000064101', (SELECT id FROM as_chat_sessions WHERE public_id = '00000000-0000-4000-8000-000000064001'), 'USER', 'PC가 부팅은 되는데 인터넷이 안 돼요. 갑자기 어제부터 그랬습니다.', '2026-07-03T22:45:00Z'),
  ('00000000-0000-4000-8000-000000064102', (SELECT id FROM as_chat_sessions WHERE public_id = '00000000-0000-4000-8000-000000064001'), 'ADMIN', '불편을 드려 죄송합니다. 원격으로 확인 후 도와드리겠습니다. 가능한 시간대가 언제이신가요?', '2026-07-03T22:46:00Z'),
  ('00000000-0000-4000-8000-000000064103', (SELECT id FROM as_chat_sessions WHERE public_id = '00000000-0000-4000-8000-000000064001'), 'USER', '오늘 저녁 8시 이후 괜찮습니다.', '2026-07-03T22:47:00Z'),
  ('00000000-0000-4000-8000-000000064104', (SELECT id FROM as_chat_sessions WHERE public_id = '00000000-0000-4000-8000-000000064001'), 'ADMIN', '네, 8시 이후 원격 지원 진행하겠습니다. 감사합니다.', '2026-07-03T22:48:00Z'),
  ('00000000-0000-4000-8000-000000064201', (SELECT id FROM as_chat_sessions WHERE public_id = '00000000-0000-4000-8000-000000064002'), 'USER', '프린터가 인쇄가 안 돼요.', '2026-07-03T22:35:00Z')
ON CONFLICT (public_id) DO NOTHING;

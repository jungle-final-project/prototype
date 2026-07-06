ALTER TABLE as_chat_sessions
  ADD COLUMN IF NOT EXISTS last_message_preview VARCHAR(240),
  ADD COLUMN IF NOT EXISTS last_message_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS user_unread_count INTEGER NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS admin_unread_count INTEGER NOT NULL DEFAULT 0;

ALTER TABLE as_chat_messages
  ADD COLUMN IF NOT EXISTS sender_user_id BIGINT REFERENCES users(id);

ALTER TABLE as_chat_messages
  DROP CONSTRAINT IF EXISTS chk_as_chat_messages_role;

ALTER TABLE as_chat_messages
  ADD CONSTRAINT chk_as_chat_messages_role
  CHECK (role IN ('USER', 'ADMIN', 'SYSTEM', 'ASSISTANT'));

CREATE INDEX IF NOT EXISTS idx_as_chat_sessions_last_message_at
  ON as_chat_sessions(last_message_at);

CREATE INDEX IF NOT EXISTS idx_as_chat_messages_sender_user_id
  ON as_chat_messages(sender_user_id);

WITH latest_message AS (
  SELECT DISTINCT ON (chat_session_id)
         chat_session_id,
         left(content, 240) AS preview,
         created_at
  FROM as_chat_messages
  ORDER BY chat_session_id, created_at DESC, id DESC
)
UPDATE as_chat_sessions s
SET last_message_preview = COALESCE(s.last_message_preview, latest_message.preview),
    last_message_at = COALESCE(s.last_message_at, latest_message.created_at),
    updated_at = COALESCE(s.updated_at, latest_message.created_at)
FROM latest_message
WHERE latest_message.chat_session_id = s.id;

INSERT INTO as_chat_sessions (
  user_id,
  as_ticket_id,
  status,
  title,
  last_message_preview,
  last_message_at,
  created_at,
  updated_at
)
SELECT t.user_id,
       t.id,
       'ACTIVE',
       'AS 상담방',
       '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
       COALESCE(t.created_at, now()),
       COALESCE(t.created_at, now()),
       COALESCE(t.created_at, now())
FROM as_tickets t
WHERE t.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM as_chat_sessions s
    WHERE s.as_ticket_id = t.id
      AND s.user_id = t.user_id
      AND s.status = 'ACTIVE'
      AND s.deleted_at IS NULL
  );

INSERT INTO as_chat_messages (
  chat_session_id,
  role,
  content,
  structured_payload
)
SELECT s.id,
       'SYSTEM',
       '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
       '{}'::jsonb
FROM as_chat_sessions s
JOIN as_tickets t ON t.id = s.as_ticket_id
WHERE s.title = 'AS 상담방'
  AND s.deleted_at IS NULL
  AND t.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM as_chat_messages m
    WHERE m.chat_session_id = s.id
  );

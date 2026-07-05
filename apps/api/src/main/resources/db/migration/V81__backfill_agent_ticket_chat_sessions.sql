WITH missing_agent_tickets AS (
  SELECT
    t.id AS ticket_id,
    t.public_id AS ticket_public_id,
    t.user_id,
    t.symptom,
    t.support_decision,
    t.support_routing,
    CASE
      WHEN t.support_routing ->> 'recommendedService' = 'REMOTE_SUPPORT' THEN 'REMOTE'
      WHEN t.support_routing ->> 'recommendedService' = 'VISIT_SUPPORT' THEN 'VISIT'
      ELSE 'DIAGNOSIS_ONLY'
    END AS support_request_type
  FROM as_tickets t
  JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
  WHERE t.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM as_chat_sessions s
      WHERE s.as_ticket_id = t.id
        AND s.deleted_at IS NULL
        AND s.status IN ('ACTIVE', 'ADMIN_REVIEWING', 'TICKET_CREATED')
    )
),
inserted_sessions AS (
  INSERT INTO as_chat_sessions (
    user_id,
    as_ticket_id,
    status,
    title,
    support_request_type,
    last_message_preview,
    last_message_at,
    admin_unread_count,
    user_unread_count,
    ticket_draft,
    created_at,
    updated_at
  )
  SELECT
    user_id,
    ticket_id,
    'ACTIVE',
    LEFT(COALESCE(NULLIF(REPLACE(SPLIT_PART(symptom, E'\n', 1), '[증상 제목]', ''), ''), 'PC Agent AS 접수'), 80),
    support_request_type,
    '접수되었습니다. 홈페이지에서 채팅 상담을 진행하세요.',
    now(),
    1,
    0,
    jsonb_build_object(
      'source', 'PC_AGENT_LOG_UPLOAD',
      'ticketId', ticket_public_id::text,
      'supportDecision', COALESCE(support_decision, 'NEEDS_MORE_INFO'),
      'recommendedService', COALESCE(support_routing ->> 'recommendedService', 'DIAGNOSIS_ONLY')
    ),
    now(),
    now()
  FROM missing_agent_tickets
  RETURNING id, public_id, as_ticket_id
)
INSERT INTO as_chat_messages (
  chat_session_id,
  role,
  content,
  structured_payload,
  created_at
)
SELECT
  s.id,
  'SYSTEM',
  '접수되었습니다. 홈페이지에서 채팅 상담을 진행하세요.',
  jsonb_build_object(
    'ticketId', t.public_id::text,
    'source', 'PC_AGENT_LOG_UPLOAD'
  ),
  now()
FROM inserted_sessions s
JOIN as_tickets t ON t.id = s.as_ticket_id;

-- P2: V93/V94 전환 중 누락될 수 있는 support chat room 백필을 idempotent하게 보정한다.
-- 티켓 종료만으로 room을 archive하지 않는 정책은 유지하고, 읽기 가능한 상담 기록을 보존한다.

WITH latest_archived_room AS (
  SELECT r.id,
         ROW_NUMBER() OVER (
           PARTITION BY r.user_id, r.as_ticket_id
           ORDER BY COALESCE(r.updated_at, r.created_at) DESC, r.id DESC
         ) AS row_number
  FROM support_chat_rooms r
  JOIN as_tickets t ON t.id = r.as_ticket_id
  WHERE r.status = 'ARCHIVED'
    AND r.deleted_at IS NULL
    AND t.deleted_at IS NULL
    AND NOT EXISTS (
      SELECT 1
      FROM support_chat_rooms active_room
      WHERE active_room.user_id = r.user_id
        AND active_room.as_ticket_id = r.as_ticket_id
        AND active_room.status = 'ACTIVE'
        AND active_room.deleted_at IS NULL
    )
)
UPDATE support_chat_rooms r
SET status = 'ACTIVE',
    updated_at = now()
FROM latest_archived_room latest
WHERE r.id = latest.id
  AND latest.row_number = 1;

INSERT INTO support_chat_rooms (
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
    FROM support_chat_rooms r
    WHERE r.as_ticket_id = t.id
      AND r.user_id = t.user_id
      AND r.deleted_at IS NULL
  );

INSERT INTO support_chat_messages (
  room_id,
  role,
  content,
  created_at
)
SELECT r.id,
       'SYSTEM',
       '상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.',
       COALESCE(r.created_at, now())
FROM support_chat_rooms r
JOIN as_tickets t ON t.id = r.as_ticket_id
WHERE r.deleted_at IS NULL
  AND t.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM support_chat_messages m
    WHERE m.room_id = r.id
  );

WITH latest_message AS (
  SELECT DISTINCT ON (room_id)
         room_id,
         left(content, 240) AS preview,
         created_at
  FROM support_chat_messages
  ORDER BY room_id, created_at DESC, id DESC
)
UPDATE support_chat_rooms r
SET last_message_preview = COALESCE(r.last_message_preview, latest_message.preview),
    last_message_at = COALESCE(r.last_message_at, latest_message.created_at),
    updated_at = COALESCE(r.updated_at, latest_message.created_at, now())
FROM latest_message
WHERE latest_message.room_id = r.id
  AND r.deleted_at IS NULL
  AND (
    r.last_message_preview IS NULL
    OR r.last_message_at IS NULL
    OR r.updated_at IS NULL
  );

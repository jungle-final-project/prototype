-- The seeded Demo User must be able to start the presentation AS flow on a clean DB.
-- Only the two fixed V7 demo tickets are closed; real user-created tickets are untouched.
UPDATE support_chat_rooms room
SET status = 'ARCHIVED',
    last_message_preview = '데모 초기 상담은 종료되었습니다.',
    updated_at = now()
FROM as_tickets ticket
WHERE room.as_ticket_id = ticket.id
  AND ticket.public_id IN (
    '00000000-0000-4000-8000-000000006001'::uuid,
    '00000000-0000-4000-8000-000000006002'::uuid
  )
  AND room.status = 'ACTIVE'
  AND room.deleted_at IS NULL;

UPDATE as_tickets
SET status = 'CANCELLED',
    updated_at = now()
WHERE public_id IN (
    '00000000-0000-4000-8000-000000006001'::uuid,
    '00000000-0000-4000-8000-000000006002'::uuid
  )
  AND status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'RESOLVED')
  AND deleted_at IS NULL;

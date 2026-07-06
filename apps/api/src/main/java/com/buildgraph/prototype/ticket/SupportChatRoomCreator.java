package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 사용자-관리자 상담방 생성을 멱등하게 보장한다.
 *
 * <p>{@code support_chat_rooms}에는 {@code (user_id, as_ticket_id)} partial unique index가
 * ({@code WHERE status='ACTIVE' AND deleted_at IS NULL}) 걸려 있어 active 상담방이 사용자·티켓당
 * 1개로 제한된다. 티켓 생성 후처리와 위젯의 상담방 진입이 동시/재시도로 겹치면 단순 INSERT는
 * unique violation으로 트랜잭션을 롤백시킨다. 이 헬퍼는 {@code ON CONFLICT DO NOTHING}으로
 * 충돌을 흡수하고, 새로 만든 경우에만 최초 SYSTEM 안내 메시지를 넣어 진짜 ensure로 동작한다.
 */
final class SupportChatRoomCreator {
    private SupportChatRoomCreator() {
    }

    record RoomRef(String publicId) {
    }

    static RoomRef ensureRoom(JdbcTemplate jdbcTemplate, Long userInternalId, Long ticketInternalId) {
        List<Map<String, Object>> inserted = jdbcTemplate.queryForList("""
                INSERT INTO support_chat_rooms (
                  user_id,
                  as_ticket_id,
                  title,
                  last_message_preview,
                  last_message_at,
                  updated_at
                )
                VALUES (?, ?, ?, ?, now(), now())
                ON CONFLICT (user_id, as_ticket_id) WHERE status = 'ACTIVE' AND deleted_at IS NULL
                DO NOTHING
                RETURNING id AS internal_id, public_id::text AS id
                """, userInternalId, ticketInternalId, "AS 상담방", SupportChatService.SYSTEM_OPEN_MESSAGE);

        if (!inserted.isEmpty()) {
            Map<String, Object> row = inserted.get(0);
            Long roomInternalId = longValue(row.get("internal_id"));
            jdbcTemplate.update("""
                    INSERT INTO support_chat_messages (
                      room_id,
                      role,
                      content
                    )
                    VALUES (?, 'SYSTEM', ?)
                    """, roomInternalId, SupportChatService.SYSTEM_OPEN_MESSAGE);
            return new RoomRef(DbValueMapper.string(row, "id"));
        }

        return existingRoom(jdbcTemplate, userInternalId, ticketInternalId);
    }

    private static RoomRef existingRoom(JdbcTemplate jdbcTemplate, Long userInternalId, Long ticketInternalId) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id
                        FROM support_chat_rooms
                        WHERE user_id = ?
                          AND as_ticket_id = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        ORDER BY id DESC
                        LIMIT 1
                        """, userInternalId, ticketInternalId)
                .stream()
                .findFirst()
                .map(row -> new RoomRef(DbValueMapper.string(row, "id")))
                .orElseThrow(() -> new IllegalStateException("support chat room ensure failed"));
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }
}

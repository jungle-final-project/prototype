package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class SupportChatService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORT_REQUEST_TYPES = Set.of("REMOTE", "VISIT", "DIAGNOSIS_ONLY");

    private final JdbcTemplate jdbcTemplate;

    public SupportChatService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> current(CurrentUserService.CurrentUser user) {
        return jdbcTemplate.queryForList("""
                        SELECT s.id AS internal_id,
                               s.public_id::text AS id,
                               s.user_id AS user_internal_id,
                               s.as_ticket_id,
                               s.title,
                               s.status,
                               s.support_request_type,
                               s.last_message_preview,
                               s.last_message_at,
                               s.admin_unread_count,
                               s.user_unread_count,
                               s.ticket_draft,
                               t.public_id::text AS ticket_id
                        FROM as_chat_sessions s
                        LEFT JOIN as_tickets t ON t.id = s.as_ticket_id
                        WHERE s.user_id = ?
                          AND s.deleted_at IS NULL
                          AND s.status IN ('ACTIVE', 'ADMIN_REVIEWING', 'TICKET_CREATED')
                        ORDER BY COALESCE(s.last_message_at, s.created_at) DESC, s.id DESC
                        LIMIT 1
                        """, user.internalId())
                .stream()
                .findFirst()
                .map(row -> detailFromRow(row, user.internalId()))
                .orElseGet(() -> {
                    Map<String, Object> empty = new LinkedHashMap<>();
                    empty.put("contact", null);
                    empty.put("messages", List.of());
                    empty.put("pollingIntervalMs", 5000);
                    return empty;
                });
    }

    @Transactional
    public Map<String, Object> create(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        String supportRequestType = normalize(
                request == null ? null : stringOrNull(request.get("supportRequestType")),
                "DIAGNOSIS_ONLY",
                SUPPORT_REQUEST_TYPES
        );
        String initialMessage = request == null ? null : stringOrNull(request.get("message"));
        Map<String, Object> existing = jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id
                        FROM as_chat_sessions
                        WHERE user_id = ?
                          AND deleted_at IS NULL
                          AND status IN ('ACTIVE', 'ADMIN_REVIEWING', 'TICKET_CREATED')
                        ORDER BY COALESCE(last_message_at, created_at) DESC, id DESC
                        LIMIT 1
                        """, user.internalId())
                .stream()
                .findFirst()
                .orElse(null);
        if (existing != null) {
            return contact(DbValueMapper.string(existing, "id"), user);
        }

        String sessionId = UUID.randomUUID().toString();
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_chat_sessions (
                  public_id,
                  user_id,
                  status,
                  title,
                  support_request_type,
                  last_message_preview,
                  last_message_at,
                  user_unread_count,
                  created_at,
                  updated_at
                )
                VALUES (
                  ?::uuid,
                  ?,
                  'ACTIVE',
                  'PC Agent 상담',
                  ?,
                  '접수되었습니다. 홈페이지에서 채팅 상담을 진행하세요.',
                  now(),
                  1,
                  now(),
                  now()
                )
                RETURNING id AS internal_id, public_id::text AS id
                """, sessionId, user.internalId(), supportRequestType);
        Long internalId = longValue(row, "internal_id");
        jdbcTemplate.update("""
                INSERT INTO as_chat_messages (public_id, chat_session_id, role, content, created_at)
                VALUES (?::uuid, ?, 'SYSTEM', '접수되었습니다. 홈페이지에서 채팅 상담을 진행하세요.', now())
                """, UUID.randomUUID().toString(), internalId);
        if (initialMessage != null && !initialMessage.isBlank()) {
            jdbcTemplate.update("""
                    INSERT INTO as_chat_messages (public_id, chat_session_id, role, content, created_at)
                    VALUES (?::uuid, ?, 'USER', ?, now())
                    """, UUID.randomUUID().toString(), internalId, initialMessage.trim());
            jdbcTemplate.update("""
                    UPDATE as_chat_sessions
                    SET last_message_preview = ?,
                        last_message_at = now(),
                        admin_unread_count = admin_unread_count + 1,
                        updated_at = now()
                    WHERE id = ?
                    """, truncate(initialMessage.trim(), 180), internalId);
        }
        return contact(sessionId, user);
    }

    public Map<String, Object> contact(String id, CurrentUserService.CurrentUser user) {
        Map<String, Object> row = contactRow(id, user.internalId());
        return detailFromRow(row, user.internalId());
    }

    @Transactional
    public Map<String, Object> postMessage(String id, Map<String, Object> request, CurrentUserService.CurrentUser user) {
        Map<String, Object> row = contactRow(id, user.internalId());
        String content = request == null ? null : stringOrNull(request.get("content"));
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 값이 필요합니다.");
        }
        jdbcTemplate.update("""
                INSERT INTO as_chat_messages (public_id, chat_session_id, role, content, created_at)
                VALUES (?::uuid, ?, 'USER', ?, now())
                """, UUID.randomUUID().toString(), longValue(row, "internal_id"), content.trim());
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET last_message_preview = ?,
                    last_message_at = now(),
                    admin_unread_count = admin_unread_count + 1,
                    updated_at = now()
                WHERE id = ?
                """, truncate(content.trim(), 180), longValue(row, "internal_id"));
        return contact(id, user);
    }

    private Map<String, Object> detailFromRow(Map<String, Object> row, Long userInternalId) {
        List<Map<String, Object>> messages = jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       role,
                       content,
                       attachment_metadata,
                       read_at,
                       created_at
                FROM as_chat_messages
                WHERE chat_session_id = ?
                ORDER BY created_at ASC, id ASC
                """, longValue(row, "internal_id")).stream().map(message -> MockData.map(
                "id", DbValueMapper.string(message, "id"),
                "role", DbValueMapper.string(message, "role"),
                "content", DbValueMapper.string(message, "content"),
                "attachmentMetadata", DbValueMapper.json(message, "attachment_metadata", Map.of()),
                "readAt", DbValueMapper.timestamp(message, "read_at"),
                "createdAt", DbValueMapper.timestamp(message, "created_at")
        )).toList();
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET user_unread_count = 0,
                    updated_at = now()
                WHERE id = ?
                  AND user_id = ?
                """, longValue(row, "internal_id"), userInternalId);
        return MockData.map(
                "contact", contactSummary(row),
                "messages", messages,
                "pollingIntervalMs", 5000
        );
    }

    private Map<String, Object> contactSummary(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "title", DbValueMapper.string(row, "title"),
                "status", DbValueMapper.string(row, "status"),
                "supportRequestType", DbValueMapper.string(row, "support_request_type"),
                "lastMessagePreview", DbValueMapper.string(row, "last_message_preview"),
                "lastMessageAt", DbValueMapper.timestamp(row, "last_message_at"),
                "adminUnreadCount", row.get("admin_unread_count"),
                "userUnreadCount", row.get("user_unread_count"),
                "ticketId", DbValueMapper.string(row, "ticket_id"),
                "ticketDraft", DbValueMapper.json(row, "ticket_draft", Map.of())
        );
    }

    private Map<String, Object> contactRow(String id, Long userInternalId) {
        validatePublicUuid("sessionId", id);
        return jdbcTemplate.queryForList("""
                        SELECT s.id AS internal_id,
                               s.public_id::text AS id,
                               s.user_id AS user_internal_id,
                               s.as_ticket_id,
                               s.title,
                               s.status,
                               s.support_request_type,
                               s.last_message_preview,
                               s.last_message_at,
                               s.admin_unread_count,
                               s.user_unread_count,
                               s.ticket_draft,
                               t.public_id::text AS ticket_id
                        FROM as_chat_sessions s
                        LEFT JOIN as_tickets t ON t.id = s.as_ticket_id
                        WHERE s.public_id = ?::uuid
                          AND s.user_id = ?
                          AND s.deleted_at IS NULL
                        """, id, userInternalId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담방을 찾을 수 없습니다."));
    }

    private static String normalize(String value, String fallback, Set<String> allowed) {
        String candidate = value == null || value.isBlank() ? fallback : value.trim();
        if (!allowed.contains(candidate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "허용되지 않은 값입니다: " + candidate);
        }
        return candidate;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(String.valueOf(value));
    }

    private static String truncate(String value, int maxLength) {
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static void validatePublicUuid(String field, String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be UUID.");
        }
    }

    @SuppressWarnings("unused")
    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 직렬화 실패", exception);
        }
    }
}

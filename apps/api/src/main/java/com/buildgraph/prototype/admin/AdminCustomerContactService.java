package com.buildgraph.prototype.admin;

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
public class AdminCustomerContactService {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> SUPPORT_REQUEST_TYPES = Set.of("REMOTE", "VISIT", "DIAGNOSIS_ONLY");
    private static final Set<String> SYMPTOM_TYPES = Set.of(
            "NETWORK_INTERNET",
            "DISPLAY_DRIVER",
            "BOOT_FAILURE",
            "PERIPHERAL_PRINTER",
            "SYSTEM_SLOW",
            "OTHER"
    );

    private final JdbcTemplate jdbcTemplate;

    public AdminCustomerContactService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> contacts() {
        List<Map<String, Object>> items = jdbcTemplate.queryForList("""
                SELECT s.public_id::text AS id,
                       s.title,
                       s.status,
                       s.support_request_type,
                       s.last_message_preview,
                       s.last_message_at,
                       s.admin_unread_count,
                       s.user_unread_count,
                       s.ticket_draft,
                       u.public_id::text AS user_id,
                       u.name AS user_name,
                       u.email AS user_email,
                       admin.public_id::text AS assigned_admin_id,
                       t.public_id::text AS ticket_id
                FROM as_chat_sessions s
                JOIN users u ON u.id = s.user_id
                LEFT JOIN users admin ON admin.id = s.assigned_admin_id
                LEFT JOIN as_tickets t ON t.id = s.as_ticket_id
                WHERE s.deleted_at IS NULL
                ORDER BY COALESCE(s.last_message_at, s.created_at) DESC, s.id DESC
                """).stream().map(this::contactSummary).toList();
        return Map.of(
                "items", items,
                "userIntegration", userIntegrationContract()
        );
    }

    public Map<String, Object> contact(String id) {
        Map<String, Object> row = contactRow(id);
        return contactDetail(row);
    }

    @Transactional
    public Map<String, Object> postMessage(String id, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> row = contactRow(id);
        String content = request == null ? null : stringOrNull(request.get("content"));
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "content 값이 필요합니다.");
        }
        String messageId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO as_chat_messages (public_id, chat_session_id, role, content, created_at)
                VALUES (?::uuid, ?, 'ADMIN', ?, now())
                """, messageId, longValue(row, "internal_id"), content.trim());
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET assigned_admin_id = COALESCE(assigned_admin_id, ?),
                    last_message_preview = ?,
                    last_message_at = now(),
                    user_unread_count = user_unread_count + 1,
                    updated_at = now()
                WHERE id = ?
                """, admin.internalId(), truncate(content.trim(), 180), longValue(row, "internal_id"));
        return contact(id);
    }

    @Transactional
    public Map<String, Object> createTicket(String id, Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, Object> row = contactRow(id);
        if (row.get("as_ticket_id") != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 티켓이 생성된 상담방입니다.");
        }
        Map<String, Object> ticketDraft = jsonMap(row, "ticket_draft");
        String symptomType = normalize(
                request == null ? null : stringOrNull(request.get("symptomType")),
                "NETWORK_INTERNET",
                SYMPTOM_TYPES
        );
        String supportRequestType = normalize(
                request == null ? null : stringOrNull(request.get("supportRequestType")),
                DbValueMapper.string(row, "support_request_type"),
                SUPPORT_REQUEST_TYPES
        );
        String symptomSummary = request == null ? null : stringOrNull(request.get("symptomSummary"));
        if (symptomSummary == null || symptomSummary.isBlank()) {
            symptomSummary = string(ticketDraft, "symptom", "상담 내용을 기반으로 생성된 AS 접수");
        }
        String adminNote = request == null ? null : stringOrNull(request.get("adminNote"));
        String preferredScheduleAt = request == null ? null : stringOrNull(request.get("preferredScheduleAt"));
        String ticketPublicId = UUID.randomUUID().toString();
        String supportDecision = string(ticketDraft, "supportDecision", switch (supportRequestType) {
            case "REMOTE" -> "REMOTE_POSSIBLE";
            case "VISIT" -> "VISIT_REQUIRED";
            default -> "NEEDS_MORE_INFO";
        });
        Long logUploadInternalId = resolveLogUploadInternalId(ticketDraft);
        Map<String, Object> ticket = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  public_id,
                  user_id,
                  log_upload_id,
                  assigned_admin_id,
                  source_chat_session_id,
                  symptom,
                  status,
                  analysis_status,
                  review_status,
                  support_decision,
                  risk_level,
                  auto_response_allowed,
                  cause_candidates,
                  upgrade_candidates,
                  admin_note,
                  incident_window,
                  log_summary,
                  support_routing,
                  ai_diagnosis_request,
                  safety_advice_level,
                  safety_notices,
                  updated_at
                )
                VALUES (
                  ?::uuid,
                  ?,
                  ?,
                  ?,
                  ?,
                  ?,
                  'OPEN',
                  'RULE_READY',
                  'REQUIRED',
                  ?,
                  ?,
                  false,
                  ?::jsonb,
                  ?::jsonb,
                  ?,
                  ?::jsonb,
                  ?::jsonb,
                  ?::jsonb,
                  ?::jsonb,
                  ?,
                  ?::jsonb,
                  now()
                )
                RETURNING id, public_id::text AS ticket_id
                """,
                ticketPublicId,
                longValue(row, "user_internal_id"),
                logUploadInternalId,
                admin.internalId(),
                longValue(row, "internal_id"),
                symptomSummary,
                supportDecision,
                string(ticketDraft, "riskLevel", "MEDIUM"),
                toJson(objectOrDefault(ticketDraft.get("causeCandidates"), List.of())),
                toJson(objectOrDefault(ticketDraft.get("upgradeCandidates"), List.of())),
                adminNote,
                toJson(objectOrDefault(ticketDraft.get("incidentWindow"), Map.of())),
                toJson(objectOrDefault(ticketDraft.get("logSummary"), Map.of())),
                toJson(mergeMaps(jsonMapValue(ticketDraft.get("supportRouting")), MockData.map(
                        "source", "ADMIN_CHAT_CONTACT",
                        "supportRequestType", supportRequestType,
                        "recommendedDecision", supportDecision,
                        "symptomType", symptomType,
                        "preferredScheduleAt", preferredScheduleAt
                ))),
                toJson(objectOrDefault(ticketDraft.get("aiDiagnosisRequest"), MockData.map(
                        "source", "ADMIN_CHAT_CONTACT",
                        "chatSessionId", id,
                        "symptomType", symptomType,
                        "rawSamples", List.of()
                ))),
                string(ticketDraft, "safetyAdviceLevel", "NONE"),
                toJson(objectOrDefault(ticketDraft.get("safetyNotices"), List.of())));
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET as_ticket_id = ?,
                    assigned_admin_id = COALESCE(assigned_admin_id, ?),
                    status = 'TICKET_CREATED',
                    ticket_draft = ?::jsonb,
                    updated_at = now()
                WHERE id = ?
                """,
                longValue(ticket, "id"),
                admin.internalId(),
                toJson(mergeMaps(ticketDraft, MockData.map(
                        "ticketId", DbValueMapper.string(ticket, "ticket_id"),
                        "symptomType", symptomType,
                        "symptomSummary", symptomSummary,
                        "supportRequestType", supportRequestType,
                        "preferredScheduleAt", preferredScheduleAt,
                        "adminNote", adminNote
                ))),
                longValue(row, "internal_id"));
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (actor_user_id, action, target_type, target_id, metadata, created_at)
                VALUES (?, 'AS_TICKET_CREATED_FROM_CONTACT', 'as_tickets', ?::uuid, ?::jsonb, now())
                """,
                admin.internalId(),
                DbValueMapper.string(ticket, "ticket_id"),
                toJson(MockData.map("sourceChatSessionId", id)));
        return contact(id);
    }

    @Transactional
    public Map<String, Object> archive(String id, CurrentUserService.CurrentUser admin) {
        Map<String, Object> row = contactRow(id);
        jdbcTemplate.update("""
                UPDATE as_chat_sessions
                SET status = 'ARCHIVED',
                    assigned_admin_id = COALESCE(assigned_admin_id, ?),
                    deleted_at = now(),
                    updated_at = now()
                WHERE id = ?
                """, admin.internalId(), longValue(row, "internal_id"));
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (actor_user_id, action, target_type, target_id, metadata, created_at)
                VALUES (?, 'AS_CHAT_SESSION_ARCHIVED', 'as_chat_sessions', ?::uuid, '{}'::jsonb, now())
                """, admin.internalId(), id);
        return Map.of("id", id, "status", "ARCHIVED");
    }

    private Map<String, Object> contactDetail(Map<String, Object> row) {
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
        Map<String, Object> summary = contactSummary(row);
        return MockData.map(
                "contact", summary,
                "messages", messages,
                "ticketDraft", DbValueMapper.json(row, "ticket_draft", Map.of()),
                "userIntegration", userIntegrationContract()
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
                "userId", DbValueMapper.string(row, "user_id"),
                "userName", DbValueMapper.string(row, "user_name"),
                "userEmail", DbValueMapper.string(row, "user_email"),
                "assignedAdminId", DbValueMapper.string(row, "assigned_admin_id"),
                "ticketId", DbValueMapper.string(row, "ticket_id"),
                "ticketDraft", DbValueMapper.json(row, "ticket_draft", Map.of())
        );
    }

    private Map<String, Object> contactRow(String id) {
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
                               u.public_id::text AS user_id,
                               u.name AS user_name,
                               u.email AS user_email,
                               admin.public_id::text AS assigned_admin_id,
                               t.public_id::text AS ticket_id
                        FROM as_chat_sessions s
                        JOIN users u ON u.id = s.user_id
                        LEFT JOIN users admin ON admin.id = s.assigned_admin_id
                        LEFT JOIN as_tickets t ON t.id = s.as_ticket_id
                        WHERE s.deleted_at IS NULL
                          AND s.public_id = ?::uuid
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "상담방을 찾을 수 없습니다."));
    }

    private Long resolveLogUploadInternalId(Map<String, Object> ticketDraft) {
        String logUploadId = string(ticketDraft, "logUploadId", null);
        if (logUploadId == null || logUploadId.isBlank()) {
            return null;
        }
        validatePublicUuid("logUploadId", logUploadId);
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                        """, logUploadId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMap(Map<String, Object> row, String key) {
        Object value = DbValueMapper.json(row, key, Map.of());
        return jsonMapValue(value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> jsonMapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private static Object objectOrDefault(Object value, Object fallback) {
        return value == null ? fallback : value;
    }

    private static Map<String, Object> mergeMaps(Map<String, Object> base, Map<String, Object> overrides) {
        Map<String, Object> merged = new LinkedHashMap<>(base);
        merged.putAll(overrides);
        return merged;
    }

    private static Map<String, Object> userIntegrationContract() {
        return MockData.map(
                "implemented", true,
                "plannedCreateEndpoint", "POST /api/support/chat-sessions",
                "plannedCurrentEndpoint", "GET /api/support/chat-sessions/current",
                "plannedMessagesEndpoint", "GET/POST /api/support/chat-sessions/{sessionId}/messages",
                "webSocketEndpoint", "GET /ws/support-chat?token={accessToken}&mode=user|admin&sessionId={chatSessionId}",
                "note", "사용자/관리자 채팅은 WebSocket을 우선 사용하고 REST API는 fallback 조회/전송 경로로 유지합니다."
        );
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

    private static String string(Map<String, Object> row, String key, String fallback) {
        Object value = row.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? fallback : text;
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

    private static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("JSON 직렬화 실패", exception);
        }
    }
}

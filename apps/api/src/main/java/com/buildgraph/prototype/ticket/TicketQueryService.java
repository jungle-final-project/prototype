package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketQueryService {
    private static final Set<String> TICKET_STATUSES = Set.of(
            "OPEN", "ASSIGNED", "IN_PROGRESS", "RESOLVED", "CLOSED", "CANCELLED"
    );
    private static final Set<String> REVIEW_STATUSES = Set.of(
            "NOT_REQUIRED", "REQUIRED", "IN_REVIEW", "APPROVED", "REJECTED"
    );
    private static final Set<String> SUPPORT_DECISIONS = Set.of(
            "SELF_SOLVABLE", "REMOTE_POSSIBLE", "VISIT_REQUIRED", "NEEDS_MORE_INFO"
    );
    private static final Set<String> RISK_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> VISIT_TIME_SLOTS = Set.of("MORNING", "AFTERNOON", "EVENING");

    private final JdbcTemplate jdbcTemplate;

    public TicketQueryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<Map<String, Object>> tickets() {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL ORDER BY t.created_at DESC, t.id DESC")
                .stream()
                .map(this::ticketMap)
                .toList();
    }

    public Map<String, Object> create(Map<String, Object> request) {
        String symptom = request == null ? "게임 중 프레임 급락" : String.valueOf(request.getOrDefault("symptom", "게임 중 프레임 급락"));
        String logUploadId = request == null ? null : stringOrNull(request.get("logUploadId"));
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  cause_candidates,
                  upgrade_candidates
                )
                VALUES (
                  (SELECT id FROM users WHERE email = 'user@example.com'),
                  (SELECT id FROM agent_log_uploads WHERE public_id = ?::uuid),
                  ?,
                  'OPEN',
                  '[]'::jsonb,
                  '[]'::jsonb
                )
                RETURNING public_id::text AS id
                """, logUploadId, symptom);
        return ticket(DbValueMapper.string(row, "id"));
    }

    public Map<String, Object> create(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        if (user == null) {
            return create(request);
        }
        String symptom = request == null
                ? "게임 중 프레임 급락"
                : String.valueOf(request.getOrDefault("symptom", "게임 중 프레임 급락"));
        Long logUploadInternalId = resolveUserLogUploadId(
                request == null ? null : stringOrNull(request.get("logUploadId")),
                user.internalId()
        );
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO as_tickets (
                  user_id,
                  log_upload_id,
                  symptom,
                  status,
                  cause_candidates,
                  upgrade_candidates
                )
                VALUES (
                  ?,
                  ?,
                  ?,
                  'OPEN',
                  '[]'::jsonb,
                  '[]'::jsonb
                )
                RETURNING public_id::text AS id
                """, user.internalId(), logUploadInternalId, symptom);
        return ticket(DbValueMapper.string(row, "id"), user);
    }

    public Map<String, Object> ticket(String id) {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    public Map<String, Object> ticket(String id, CurrentUserService.CurrentUser user) {
        if (user == null) {
            return ticket(id);
        }
        return jdbcTemplate.queryForList(
                        ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid AND t.user_id = ?",
                        id,
                        user.internalId()
                )
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private Long resolveUserLogUploadId(String logUploadId, Long userInternalId) {
        if (logUploadId == null) {
            return null;
        }
        return jdbcTemplate.queryForList("""
                        SELECT id
                        FROM agent_log_uploads
                        WHERE public_id = ?::uuid
                          AND user_id = ?
                        """, logUploadId, userInternalId)
                .stream()
                .findFirst()
                .map(row -> longValue(row, "id"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "로그 업로드를 찾을 수 없습니다."));
    }

    public Map<String, Object> update(String id, Map<String, Object> request) {
        return update(id, request, null);
    }

    public Map<String, Object> update(
            String id,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        Map<String, Object> current = ticketRow(id);
        String status = request == null ? null : stringOrNull(request.get("status"));
        String adminNote = request == null ? null : stringOrNull(request.get("adminNote"));
        if (status != null) {
            validateStatusTransition(DbValueMapper.string(current, "status"), status);
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET status = ?, updated_at = now()
                    WHERE public_id = ?::uuid
                    """, status, id);
        }
        if (adminNote != null) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET admin_note = ?, updated_at = now()
                    WHERE public_id = ?::uuid
                    """, adminNote, id);
        }
        String assignedAdminId = request == null ? null : stringOrNull(request.get("assignedAdminId"));
        if (assignedAdminId != null) {
            assignAdmin(id, assignedAdminId);
        }
        String supportDecision = request == null ? null : stringOrNull(request.get("supportDecision"));
        String reviewStatus = request == null ? null : stringOrNull(request.get("reviewStatus"));
        String riskLevel = request == null ? null : stringOrNull(request.get("riskLevel"));
        validateNullable("supportDecision", supportDecision, SUPPORT_DECISIONS);
        validateNullable("reviewStatus", reviewStatus, REVIEW_STATUSES);
        validateNullable("riskLevel", riskLevel, RISK_LEVELS);
        Boolean autoResponseAllowed = request == null || request.get("autoResponseAllowed") == null
                ? null
                : parseBoolean("autoResponseAllowed", request.get("autoResponseAllowed"));
        if (supportDecision != null || reviewStatus != null || riskLevel != null || autoResponseAllowed != null) {
            jdbcTemplate.update("""
                    UPDATE as_tickets
                    SET support_decision = COALESCE(?, support_decision),
                        review_status = COALESCE(?, review_status),
                        risk_level = COALESCE(?, risk_level),
                        auto_response_allowed = COALESCE(?, auto_response_allowed),
                        updated_at = now()
                    WHERE public_id = ?::uuid
                    """,
                    supportDecision,
                    reviewStatus == null && supportDecision != null ? "APPROVED" : reviewStatus,
                    riskLevel,
                    autoResponseAllowed,
                    id
            );
        }
        saveRemoteSupportIfRequested(id, request, admin);
        saveVisitSupportIfRequested(current, request);
        auditTicketUpdate(id, current, request, admin);
        return ticket(id);
    }

    private Map<String, Object> ticketRow(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               user_id,
                               log_upload_id,
                               status,
                               review_status,
                               support_decision
                        FROM as_tickets
                        WHERE deleted_at IS NULL
                          AND public_id = ?::uuid
                        """, id)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    private void saveRemoteSupportIfRequested(
            String ticketId,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        if (request == null) {
            return;
        }
        String remoteSupportLink = stringOrNull(request.get("remoteSupportLink"));
        if (remoteSupportLink == null) {
            remoteSupportLink = stringOrNull(request.get("remoteSupportUrl"));
        }
        if (remoteSupportLink == null) {
            return;
        }
        validateRemoteSupportLink(remoteSupportLink);
        jdbcTemplate.update("""
                INSERT INTO remote_support_sessions (
                  as_ticket_id,
                  device_id,
                  provider,
                  session_url,
                  status,
                  requested_by_admin_id
                )
                SELECT t.id,
                       lu.device_id,
                       'EXTERNAL_LINK',
                       ?,
                       'LINK_SENT',
                       ?
                FROM as_tickets t
                LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                WHERE t.public_id = ?::uuid
                  AND t.deleted_at IS NULL
                """, remoteSupportLink, admin == null ? null : admin.internalId(), ticketId);
    }

    private void saveVisitSupportIfRequested(Map<String, Object> current, Map<String, Object> request) {
        if (request == null || !Boolean.TRUE.equals(booleanOrNull(request.get("visitSupportRequired")))) {
            return;
        }
        String timeSlot = stringOrNull(request.get("visitTimeSlot"));
        if (timeSlot == null) {
            timeSlot = "AFTERNOON";
        }
        validateNullable("visitTimeSlot", timeSlot, VISIT_TIME_SLOTS);
        LocalDate preferredDate = request.get("visitPreferredDate") == null
                ? LocalDate.now().plusDays(1)
                : parseDate("visitPreferredDate", request.get("visitPreferredDate"));
        jdbcTemplate.update("""
                INSERT INTO visit_support_reservations (
                  as_ticket_id,
                  user_id,
                  preferred_date,
                  time_slot,
                  status,
                  address_snapshot,
                  technician_note,
                  updated_at
                )
                VALUES (?, ?, ?, ?, 'REQUESTED', ?, ?, now())
                """,
                longValue(current, "internal_id"),
                longValue(current, "user_id"),
                preferredDate,
                timeSlot,
                stringOrNull(request.get("visitAddressSnapshot")),
                stringOrNull(request.get("visitTechnicianNote"))
        );
    }

    private void auditTicketUpdate(
            String ticketId,
            Map<String, Object> current,
            Map<String, Object> request,
            CurrentUserService.CurrentUser admin
    ) {
        if (request == null || admin == null) {
            return;
        }
        jdbcTemplate.update("""
                INSERT INTO admin_audit_logs (
                  actor_user_id,
                  action,
                  target_type,
                  target_id,
                  metadata
                )
                VALUES (
                  ?,
                  'AS_TICKET_UPDATED',
                  'as_tickets',
                  ?,
                  jsonb_build_object(
                    'beforeStatus', ?,
                    'afterStatus', COALESCE(?, ?),
                    'supportDecision', ?,
                    'reviewStatus', ?
                  )
                )
                """,
                admin.internalId(),
                ticketId,
                DbValueMapper.string(current, "status"),
                stringOrNull(request.get("status")),
                DbValueMapper.string(current, "status"),
                stringOrNull(request.get("supportDecision")),
                stringOrNull(request.get("reviewStatus"))
        );
    }

    private void assignAdmin(String ticketId, String assignedAdminId) {
        validatePublicUuid("assignedAdminId", assignedAdminId);
        Map<String, Object> adminRow = jdbcTemplate.queryForList("""
                        SELECT id
                        FROM users
                        WHERE public_id = ?::uuid
                          AND role = 'ADMIN'
                        """, assignedAdminId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "assignedAdminId must reference an ADMIN user."));
        jdbcTemplate.update("""
                UPDATE as_tickets
                SET assigned_admin_id = ?, updated_at = now()
                WHERE public_id = ?::uuid
                """, longValue(adminRow, "id"), ticketId);
    }

    private String ticketSql() {
        return """
                SELECT t.public_id::text AS id,
                       t.status,
                       t.analysis_status,
                       t.review_status,
                       t.support_decision,
                       t.risk_level,
                       t.auto_response_allowed,
                       t.symptom,
                       lu.public_id::text AS log_upload_id,
                       admin.public_id::text AS assigned_admin_id,
                       t.cause_candidates,
                       t.upgrade_candidates,
                       t.admin_note,
                       t.resolved_at,
                       t.created_at,
                       rs.session_url AS remote_support_link,
                       rs.status AS remote_support_status,
                       vr.public_id::text AS visit_support_id,
                       vr.status AS visit_support_status,
                       vr.preferred_date AS visit_preferred_date,
                       vr.time_slot AS visit_time_slot
                FROM as_tickets t
                LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                LEFT JOIN users admin ON admin.id = t.assigned_admin_id
                LEFT JOIN LATERAL (
                  SELECT session_url, status
                  FROM remote_support_sessions
                  WHERE as_ticket_id = t.id
                  ORDER BY created_at DESC, id DESC
                  LIMIT 1
                ) rs ON true
                LEFT JOIN LATERAL (
                  SELECT public_id, status, preferred_date, time_slot
                  FROM visit_support_reservations
                  WHERE as_ticket_id = t.id
                  ORDER BY created_at DESC, id DESC
                  LIMIT 1
                ) vr ON true
                """;
    }

    private Map<String, Object> ticketMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "status", DbValueMapper.string(row, "status"),
                "analysisStatus", DbValueMapper.string(row, "analysis_status"),
                "reviewStatus", DbValueMapper.string(row, "review_status"),
                "supportDecision", DbValueMapper.string(row, "support_decision"),
                "riskLevel", DbValueMapper.string(row, "risk_level"),
                "autoResponseAllowed", row.get("auto_response_allowed"),
                "symptom", DbValueMapper.string(row, "symptom"),
                "logUploadId", DbValueMapper.string(row, "log_upload_id"),
                "assignedAdminId", DbValueMapper.string(row, "assigned_admin_id"),
                "causeCandidates", DbValueMapper.json(row, "cause_candidates", List.of()),
                "upgradeCandidates", DbValueMapper.json(row, "upgrade_candidates", List.of()),
                "adminNote", DbValueMapper.string(row, "admin_note"),
                "remoteSupportLink", DbValueMapper.string(row, "remote_support_link"),
                "remoteSupportStatus", DbValueMapper.string(row, "remote_support_status"),
                "visitSupportRequired", row.get("visit_support_id") != null,
                "visitSupportStatus", DbValueMapper.string(row, "visit_support_status"),
                "visitPreferredDate", row.get("visit_preferred_date"),
                "visitTimeSlot", DbValueMapper.string(row, "visit_time_slot"),
                "resolvedAt", DbValueMapper.timestamp(row, "resolved_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static void validateStatusTransition(String before, String after) {
        validateNullable("status", after, TICKET_STATUSES);
        boolean allowed = switch (before) {
            case "OPEN" -> Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CANCELLED").contains(after);
            case "ASSIGNED" -> Set.of("ASSIGNED", "IN_PROGRESS", "RESOLVED", "CANCELLED").contains(after);
            case "IN_PROGRESS" -> Set.of("ASSIGNED", "RESOLVED", "CANCELLED").contains(after);
            case "RESOLVED" -> "CLOSED".equals(after);
            default -> false;
        };
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "AS 티켓 상태 전이가 허용되지 않습니다.");
        }
    }

    private static void validateNullable(String fieldName, String value, Set<String> allowedValues) {
        if (value != null && !allowedValues.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.");
        }
    }

    private static Boolean parseBoolean(String fieldName, Object value) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        String text = value == null ? null : value.toString();
        if ("true".equalsIgnoreCase(text)) {
            return true;
        }
        if ("false".equalsIgnoreCase(text)) {
            return false;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.");
    }

    private static LocalDate parseDate(String fieldName, Object value) {
        try {
            return LocalDate.parse(value.toString());
        } catch (DateTimeParseException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " 값이 올바르지 않습니다.", exception);
        }
    }

    private static void validateRemoteSupportLink(String value) {
        if (value.length() > 2_000 || value.chars().anyMatch(Character::isWhitespace)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remoteSupportLink 값이 올바르지 않습니다.");
        }
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme() == null ? null : uri.getScheme().toLowerCase();
            if (uri.getHost() == null || (!"http".equals(scheme) && !"https".equals(scheme))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remoteSupportLink 값이 올바르지 않습니다.");
            }
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "remoteSupportLink 값이 올바르지 않습니다.", exception);
        }
    }

    private static Boolean booleanOrNull(Object value) {
        return value == null ? null : parseBoolean("boolean", value);
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static void validatePublicUuid(String fieldName, String value) {
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, fieldName + " must be a UUID.");
        }
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}

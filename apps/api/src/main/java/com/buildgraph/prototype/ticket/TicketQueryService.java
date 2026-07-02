package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TicketQueryService {
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

    public Map<String, Object> ticket(String id) {
        return jdbcTemplate.queryForList(ticketSql() + " WHERE t.deleted_at IS NULL AND t.public_id = ?::uuid", id)
                .stream()
                .findFirst()
                .map(this::ticketMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AS 티켓을 찾을 수 없습니다."));
    }

    public Map<String, Object> update(String id, Map<String, Object> request) {
        String status = request == null ? null : stringOrNull(request.get("status"));
        String adminNote = request == null ? null : stringOrNull(request.get("adminNote"));
        if (status != null) {
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
        String supportDecision = request == null ? null : stringOrNull(request.get("supportDecision"));
        String reviewStatus = request == null ? null : stringOrNull(request.get("reviewStatus"));
        String riskLevel = request == null ? null : stringOrNull(request.get("riskLevel"));
        Boolean autoResponseAllowed = request == null || request.get("autoResponseAllowed") == null
                ? null
                : Boolean.valueOf(request.get("autoResponseAllowed").toString());
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
        return ticket(id);
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
                       t.created_at
                FROM as_tickets t
                LEFT JOIN agent_log_uploads lu ON lu.id = t.log_upload_id
                LEFT JOIN users admin ON admin.id = t.assigned_admin_id
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
                "resolvedAt", DbValueMapper.timestamp(row, "resolved_at"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : value.toString();
    }
}

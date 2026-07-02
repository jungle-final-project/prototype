package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class TicketQueryServiceTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final TicketQueryService service = new TicketQueryService(jdbcTemplate);
    private final CurrentUserService.CurrentUser admin = new CurrentUserService.CurrentUser(
            1L,
            "admin-public-id",
            "admin@example.com",
            "Admin",
            "ADMIN",
            null
    );

    @Test
    void updateStoresSupportDecisionRemoteLinkVisitRequestAndAuditLog() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN",
                        "review_status", "REQUIRED",
                        "support_decision", "NEEDS_MORE_INFO"
                )))
                .thenReturn(List.of(MockData.map(
                        "id", "ticket-public-id",
                        "status", "IN_PROGRESS",
                        "analysis_status", "RULE_READY",
                        "review_status", "APPROVED",
                        "support_decision", "REMOTE_POSSIBLE",
                        "risk_level", "MEDIUM",
                        "auto_response_allowed", false,
                        "symptom", "GPU temperature spike",
                        "log_upload_id", "log-upload-public-id",
                        "assigned_admin_id", "admin-public-id",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "admin_note", "Remote support link sent.",
                        "remote_support_link", "https://support.example/session/1",
                        "remote_support_status", "LINK_SENT",
                        "visit_support_id", "visit-public-id",
                        "visit_support_status", "REQUESTED",
                        "visit_preferred_date", LocalDate.parse("2026-07-03"),
                        "visit_time_slot", "AFTERNOON"
                )));

        service.update("ticket-public-id", MockData.map(
                "status", "IN_PROGRESS",
                "supportDecision", "REMOTE_POSSIBLE",
                "reviewStatus", "APPROVED",
                "adminNote", "Remote support link sent.",
                "remoteSupportLink", "https://support.example/session/1",
                "visitSupportRequired", true,
                "visitPreferredDate", "2026-07-03",
                "visitTimeSlot", "AFTERNOON"
        ), admin);

        verify(jdbcTemplate).update(contains("UPDATE as_tickets"), eq("IN_PROGRESS"), eq("ticket-public-id"));
        verify(jdbcTemplate).update(contains("remote_support_sessions"), eq("https://support.example/session/1"), eq(1L), eq("ticket-public-id"));
        verify(jdbcTemplate).update(
                contains("visit_support_reservations"),
                eq(100L),
                eq(20L),
                eq(LocalDate.parse("2026-07-03")),
                eq("AFTERNOON"),
                isNull(),
                isNull()
        );
        verify(jdbcTemplate).update(
                contains("admin_audit_logs"),
                eq(1L),
                eq("ticket-public-id"),
                eq("OPEN"),
                eq("IN_PROGRESS"),
                eq("OPEN"),
                eq("REMOTE_POSSIBLE"),
                eq("APPROVED")
        );
    }

    @Test
    void updateRejectsInvalidTicketStatusTransition() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "RESOLVED"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "status", "IN_PROGRESS"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.CONFLICT));
    }

    @Test
    void updateRejectsUnknownSupportDecision() {
        when(jdbcTemplate.queryForList(contains("FROM as_tickets"), eq("ticket-public-id")))
                .thenReturn(List.of(MockData.map(
                        "internal_id", 100L,
                        "id", "ticket-public-id",
                        "user_id", 20L,
                        "status", "OPEN"
                )));

        assertThatThrownBy(() -> service.update("ticket-public-id", MockData.map(
                "supportDecision", "QUICK_ASSIST"
        ), admin))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(exception -> assertThatStatus((ResponseStatusException) exception, HttpStatus.BAD_REQUEST));
    }

    private static void assertThatStatus(ResponseStatusException exception, HttpStatus status) {
        org.assertj.core.api.Assertions.assertThat(exception.getStatusCode()).isEqualTo(status);
    }
}

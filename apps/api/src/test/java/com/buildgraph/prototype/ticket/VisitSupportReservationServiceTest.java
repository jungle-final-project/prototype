package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class VisitSupportReservationServiceTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
    private static final String SCHEDULED_AT = "2099-07-10T14:30:00+09:00";
    private static final OffsetDateTime SCHEDULED_AT_VALUE = OffsetDateTime.parse(SCHEDULED_AT);
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            2L,
            "00000000-0000-4000-8000-000000000001",
            "admin@example.com",
            "BuildGraph Admin",
            "ADMIN",
            null
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SupportChatService supportChatService = mock(SupportChatService.class);
    private final VisitSupportReservationService service = new VisitSupportReservationService(
            jdbcTemplate,
            supportChatService,
            Clock.fixed(Instant.parse("2026-07-06T00:00:00Z"), ZoneId.of("Asia/Seoul"))
    );

    @Test
    void userCanRequestVisitReservationAndCreatesSystemMessage() {
        mockUserRoom("OPEN");
        mockTicketLock("OPEN");
        mockNoActiveReservation();
        mockInsertReservation("REQUESTED", "서울시 강남구", null);
        Map<String, Object> detail = chatDetail("REQUESTED");
        when(supportChatService.detailSnapshot(ROOM_ID, USER)).thenReturn(detail);

        Map<String, Object> result = service.requestUserReservation(ROOM_ID, Map.of(
                "scheduledAt", SCHEDULED_AT,
                "addressSnapshot", "서울시 강남구"
        ), USER);

        assertThat(result).isSameAs(detail);
        verify(jdbcTemplate).queryForList(
                contains("INSERT INTO visit_support_reservations"),
                eq(6001L),
                eq(USER.internalId()),
                eq(LocalDate.of(2099, 7, 10)),
                eq("AFTERNOON"),
                eq("REQUESTED"),
                eq(SCHEDULED_AT_VALUE),
                eq("서울시 강남구"),
                isNull()
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO support_chat_messages"),
                eq(7001L),
                eq("SYSTEM"),
                eq("방문 지원 예약을 요청했습니다: 2099-07-10 14:30"),
                isNull()
        );
        verify(jdbcTemplate).update(contains("admin_unread_count"), eq("방문 지원 예약을 요청했습니다: 2099-07-10 14:30"), eq(0), eq(1), eq(7001L));
    }

    @Test
    void userRescheduleUpdatesExistingActiveReservationAsRescheduleRequested() {
        mockUserRoom("OPEN");
        mockTicketLock("OPEN");
        mockActiveReservation("SCHEDULED");
        mockUpdateReservation("RESCHEDULE_REQUESTED", "서울시 서초구", null);
        when(supportChatService.detailSnapshot(ROOM_ID, USER)).thenReturn(chatDetail("RESCHEDULE_REQUESTED"));

        service.requestUserReservation(ROOM_ID, Map.of(
                "scheduledAt", SCHEDULED_AT,
                "addressSnapshot", "서울시 서초구"
        ), USER);

        verify(jdbcTemplate).queryForList(
                contains("UPDATE visit_support_reservations"),
                eq("RESCHEDULE_REQUESTED"),
                eq(LocalDate.of(2099, 7, 10)),
                eq("AFTERNOON"),
                eq(SCHEDULED_AT_VALUE),
                eq("서울시 서초구"),
                isNull(),
                eq(8001L)
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO support_chat_messages"),
                eq(7001L),
                eq("SYSTEM"),
                eq("방문 지원 예약 변경을 요청했습니다: 2099-07-10 14:30"),
                isNull()
        );
    }

    @Test
    void adminCanScheduleAndConfirmVisitReservation() {
        mockAdminRoom("OPEN");
        mockTicketLock("OPEN");
        mockNoActiveReservation();
        mockInsertReservation("SCHEDULED", null, "방문 전 연락");
        when(supportChatService.adminDetailSnapshot(ROOM_ID, ADMIN)).thenReturn(chatDetail("SCHEDULED"));

        Map<String, Object> result = service.scheduleAdminReservation(ROOM_ID, Map.of(
                "scheduledAt", SCHEDULED_AT,
                "technicianNote", "방문 전 연락"
        ), ADMIN);

        assertThat((Map<String, Object>) ((Map<String, Object>) result.get("contact")).get("visitReservation"))
                .containsEntry("status", "SCHEDULED");
        verify(jdbcTemplate).queryForList(
                contains("INSERT INTO visit_support_reservations"),
                eq(6001L),
                eq(USER.internalId()),
                eq(LocalDate.of(2099, 7, 10)),
                eq("AFTERNOON"),
                eq("SCHEDULED"),
                eq(SCHEDULED_AT_VALUE),
                isNull(),
                eq("방문 전 연락")
        );
        verify(jdbcTemplate).update(contains("user_unread_count"), eq("방문 지원 예약이 확정되었습니다: 2099-07-10 14:30"), eq(1), eq(0), eq(7001L));
    }

    @Test
    void adminCanCancelActiveVisitReservation() {
        mockAdminRoom("OPEN");
        mockTicketLock("OPEN");
        mockActiveReservation("SCHEDULED");
        when(jdbcTemplate.queryForList(contains("UPDATE visit_support_reservations"), eq("CANCELLED"), eq(8001L)))
                .thenReturn(List.of(reservationRow("CANCELLED", null, null)));
        when(supportChatService.adminDetailSnapshot(ROOM_ID, ADMIN)).thenReturn(chatDetail("CANCELLED"));

        service.cancelAdminReservation(ROOM_ID, ADMIN);

        verify(jdbcTemplate).queryForList(contains("UPDATE visit_support_reservations"), eq("CANCELLED"), eq(8001L));
        verify(jdbcTemplate).update(
                contains("INSERT INTO support_chat_messages"),
                eq(7001L),
                eq("SYSTEM"),
                eq("방문 지원 예약이 취소되었습니다."),
                isNull()
        );
    }

    @Test
    void terminalTicketCannotChangeVisitReservation() {
        mockUserRoom("CLOSED");

        assertThatThrownBy(() -> service.requestUserReservation(ROOM_ID, Map.of("scheduledAt", SCHEDULED_AT), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void invalidOrPastScheduledAtIsBadRequest() {
        assertThatThrownBy(() -> service.requestUserReservation(ROOM_ID, Map.of("scheduledAt", "not-a-date"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> service.requestUserReservation(ROOM_ID, Map.of("scheduledAt", "2020-01-01T09:00:00+09:00"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void adminCancelWithoutActiveReservationIsNotFound() {
        mockAdminRoom("OPEN");
        mockTicketLock("OPEN");
        mockNoActiveReservation();

        assertThatThrownBy(() -> service.cancelAdminReservation(ROOM_ID, ADMIN))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    private void mockUserRoom(String ticketStatus) {
        when(jdbcTemplate.queryForList(contains("FROM support_chat_rooms r"), eq(ROOM_ID), eq(USER.internalId())))
                .thenReturn(List.of(roomRow(ticketStatus)));
    }

    private void mockAdminRoom(String ticketStatus) {
        when(jdbcTemplate.queryForList(contains("FROM support_chat_rooms r"), eq(ROOM_ID)))
                .thenReturn(List.of(roomRow(ticketStatus)));
    }

    private void mockTicketLock(String status) {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(6001L))).thenReturn(List.of(Map.of("status", status)));
    }

    private void mockNoActiveReservation() {
        when(jdbcTemplate.queryForList(contains("active_visit_reservation"), eq(6001L))).thenReturn(List.of());
    }

    private void mockActiveReservation(String status) {
        when(jdbcTemplate.queryForList(contains("active_visit_reservation"), eq(6001L))).thenReturn(List.of(reservationRow(status, null, null)));
    }

    private void mockInsertReservation(String status, String address, String note) {
        when(jdbcTemplate.queryForList(
                contains("INSERT INTO visit_support_reservations"),
                eq(6001L),
                eq(USER.internalId()),
                eq(LocalDate.of(2099, 7, 10)),
                eq("AFTERNOON"),
                eq(status),
                eq(SCHEDULED_AT_VALUE),
                eq(address),
                eq(note)
        )).thenReturn(List.of(reservationRow(status, address, note)));
    }

    private void mockUpdateReservation(String status, String address, String note) {
        when(jdbcTemplate.queryForList(
                contains("UPDATE visit_support_reservations"),
                eq(status),
                eq(LocalDate.of(2099, 7, 10)),
                eq("AFTERNOON"),
                eq(SCHEDULED_AT_VALUE),
                eq(address),
                eq(note),
                eq(8001L)
        )).thenReturn(List.of(reservationRow(status, address, note)));
    }

    private static Map<String, Object> roomRow(String ticketStatus) {
        return Map.ofEntries(
                Map.entry("room_internal_id", 7001L),
                Map.entry("room_id", ROOM_ID),
                Map.entry("ticket_internal_id", 6001L),
                Map.entry("ticket_id", "00000000-0000-4000-8000-000000006001"),
                Map.entry("ticket_status", ticketStatus),
                Map.entry("user_internal_id", USER.internalId())
        );
    }

    private static Map<String, Object> reservationRow(String status, String address, String note) {
        return MockData.map(
                "internal_id", 8001L,
                "id", "00000000-0000-4000-8000-000000008001",
                "status", status,
                "scheduled_at", SCHEDULED_AT_VALUE,
                "address_snapshot", address,
                "technician_note", note,
                "created_at", "2099-07-01T00:00:00Z",
                "updated_at", "2099-07-02T00:00:00Z"
        );
    }

    private static Map<String, Object> chatDetail(String reservationStatus) {
        return MockData.map(
                "contact", MockData.map(
                        "id", ROOM_ID,
                        "visitReservation", MockData.map(
                                "id", "00000000-0000-4000-8000-000000008001",
                                "status", reservationStatus,
                                "scheduledAt", SCHEDULED_AT
                        )
                ),
                "messages", List.of()
        );
    }
}

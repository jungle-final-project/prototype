package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class SupportChatServiceTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
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
    private final SupportChatService service = new SupportChatService(jdbcTemplate);

    @Test
    void postUserMessageRejectsNonStringContent() {
        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, Map.of("content", 123), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void postUserMessageRejectsBlankNullLiteralAndTooLongContent() {
        assertBadRequest(Map.of("content", "   "));
        assertBadRequest(Map.of("content", "null"));
        assertBadRequest(Map.of("content", "x".repeat(2001)));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void postUserMessageRejectsTerminalTicketWithoutInsertingMessage() {
        mockRoom("ACTIVE", "CLOSED");

        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, Map.of("content", "아직 확인할 내용이 있습니다."), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void postUserMessageRejectsInactiveRoomWithoutInsertingMessage() {
        mockRoom("ARCHIVED", "OPEN");

        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, Map.of("content", "아직 확인할 내용이 있습니다."), USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.CONFLICT));

        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any(), any(), any());
    }

    @Test
    void userDetailSnapshotDoesNotClearUnreadCount() {
        mockRoom("ACTIVE", "OPEN", 3, 0);
        mockMessages();

        Map<String, Object> detail = service.detailSnapshot(ROOM_ID, USER);

        assertThat((Map<String, Object>) detail.get("contact")).containsEntry("userUnreadCount", 3);
        verify(jdbcTemplate, never()).update(contains("user_unread_count = 0"), eq(7001L));
    }

    @Test
    void adminDetailSnapshotDoesNotClearUnreadCount() {
        mockAdminRoom("ACTIVE", "OPEN", 0, 4);
        mockMessages();

        Map<String, Object> detail = service.adminDetailSnapshot(ROOM_ID, ADMIN);

        assertThat((Map<String, Object>) detail.get("contact")).containsEntry("adminUnreadCount", 4);
        verify(jdbcTemplate, never()).update(contains("admin_unread_count = 0"), eq(7001L));
    }

    @Test
    void closedTicketChatHistoryRemainsReadableWithoutActiveRoomFilter() {
        mockRoom("ARCHIVED", "CLOSED", 2, 0);
        mockMessages();

        Map<String, Object> detail = service.detailSnapshot(ROOM_ID, USER);

        assertThat((Map<String, Object>) detail.get("contact"))
                .containsEntry("status", "ARCHIVED")
                .containsEntry("ticketStatus", "CLOSED")
                .containsEntry("canSendMessage", false);
    }

    @Test
    void adminQueueContactSnapshotReturnsContactWhenRoomBelongsInAdminList() {
        mockAdminRoom("ACTIVE", "OPEN", 0, 4);

        Optional<Map<String, Object>> contact = service.adminQueueContactSnapshot(ROOM_ID);

        assertThat(contact).isPresent();
        assertThat(contact.orElseThrow())
                .containsEntry("id", ROOM_ID)
                .containsEntry("adminUnreadCount", 4)
                .containsEntry("canSendMessage", true);
    }

    @Test
    void adminQueueContactSnapshotIsEmptyWhenRoomIsNotInAdminList() {
        when(jdbcTemplate.queryForList(anyString(), eq(ROOM_ID))).thenReturn(List.of());

        Optional<Map<String, Object>> contact = service.adminQueueContactSnapshot(ROOM_ID);

        assertThat(contact).isEmpty();
    }

    private void assertBadRequest(Map<String, Object> request) {
        assertThatThrownBy(() -> service.postUserMessage(ROOM_ID, request, USER))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    private void mockRoom(String roomStatus, String ticketStatus) {
        mockRoom(roomStatus, ticketStatus, 0, 0);
    }

    private void mockRoom(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        when(jdbcTemplate.queryForList(anyString(), eq(ROOM_ID), eq(USER.internalId())))
                .thenReturn(List.of(roomRow(roomStatus, ticketStatus, userUnreadCount, adminUnreadCount)));
    }

    private void mockAdminRoom(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        when(jdbcTemplate.queryForList(anyString(), eq(ROOM_ID)))
                .thenReturn(List.of(roomRow(roomStatus, ticketStatus, userUnreadCount, adminUnreadCount)));
    }

    private void mockMessages() {
        when(jdbcTemplate.queryForList(anyString(), eq(7001L), eq(100)))
                .thenReturn(List.of());
    }

    private Map<String, Object> roomRow(String roomStatus, String ticketStatus, int userUnreadCount, int adminUnreadCount) {
        return Map.ofEntries(
                Map.entry("internal_id", 7001L),
                Map.entry("id", ROOM_ID),
                Map.entry("ticket_internal_id", 6001L),
                Map.entry("as_ticket_id", "00000000-0000-4000-8000-000000006001"),
                Map.entry("ticket_status", ticketStatus),
                Map.entry("ticket_symptom", "GPU 온도 상승"),
                Map.entry("status", roomStatus),
                Map.entry("title", "AS 상담방"),
                Map.entry("last_message_preview", "최근 메시지"),
                Map.entry("user_unread_count", userUnreadCount),
                Map.entry("admin_unread_count", adminUnreadCount),
                Map.entry("user_id", USER.id()),
                Map.entry("user_email", USER.email()),
                Map.entry("user_name", USER.name())
        );
    }
}

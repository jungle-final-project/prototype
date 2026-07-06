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

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class TicketQueryServiceSupportChatTest {
    private static final String SYSTEM_MESSAGE = "상담방이 생성되었습니다. 문의 내용을 남기면 담당자가 확인합니다.";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final TicketQueryService service = new TicketQueryService(jdbcTemplate);

    @Test
    void createTicketEnsuresSupportChatRoomWithSystemMessage() {
        mockLockedUser();
        mockNoOpenSupportChat();
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(USER.internalId()),
                eq(null),
                eq("GPU 온도가 급격히 올라갑니다.")
        )).thenReturn(Map.of(
                "id", "ticket-public-id",
                "internal_id", 6001L
        ));
        when(jdbcTemplate.queryForList(
                contains("INSERT INTO support_chat_rooms"),
                eq(USER.internalId()),
                eq(6001L),
                eq("AS 상담방"),
                eq(SYSTEM_MESSAGE)
        )).thenReturn(List.of(Map.of(
                "internal_id", 7001L,
                "id", "room-public-id"
        )));
        when(jdbcTemplate.queryForList(anyString(), eq("ticket-public-id"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of(
                        "id", "ticket-public-id",
                        "user_id", USER.id(),
                        "status", "OPEN",
                        "symptom", "GPU 온도가 급격히 올라갑니다.",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "support_chat_room_id", "room-public-id"
                )));

        Map<String, Object> result = service.create(Map.of("symptom", "GPU 온도가 급격히 올라갑니다."), USER);

        assertThat(result).containsEntry("id", "ticket-public-id");
        assertThat(result).containsEntry("supportChatRoomId", "room-public-id");
        verify(jdbcTemplate).queryForList(contains("FOR UPDATE"), eq(USER.internalId()));
        verify(jdbcTemplate).queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId()));
        verify(jdbcTemplate).queryForList(
                contains("INSERT INTO support_chat_rooms"),
                eq(USER.internalId()),
                eq(6001L),
                eq("AS 상담방"),
                eq(SYSTEM_MESSAGE)
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO support_chat_messages"),
                eq(7001L),
                eq(SYSTEM_MESSAGE)
        );
    }

    @Test
    void createTicketRejectsWhenUserAlreadyHasOpenSupportChat() {
        mockLockedUser();
        when(jdbcTemplate.queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of(
                        "as_ticket_id", "00000000-0000-4000-8000-000000006001",
                        "support_chat_room_id", "00000000-0000-4000-8000-000000009001"
                )));

        assertThatThrownBy(() -> service.create(Map.of("symptom", "새 증상입니다."), USER))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException exception = (ApiException) error;
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("CONFLICT_STATE");
                    assertThat(exception.details()).containsEntry("asTicketId", "00000000-0000-4000-8000-000000006001");
                    assertThat(exception.details()).containsEntry("supportChatRoomId", "00000000-0000-4000-8000-000000009001");
                });

        verify(jdbcTemplate).queryForList(contains("FOR UPDATE"), eq(USER.internalId()));
        verify(jdbcTemplate, never()).queryForMap(contains("INSERT INTO as_tickets"), any(), any(), any());
        verify(jdbcTemplate, never()).queryForList(contains("INSERT INTO support_chat_rooms"), any(), any(), any(), any());
        verify(jdbcTemplate, never()).update(contains("INSERT INTO support_chat_messages"), any(), any());
    }

    @Test
    void createTicketAllowsNewTicketWhenExistingSupportChatTicketIsClosedOrCancelled() {
        mockLockedUser();
        mockNoOpenSupportChat();
        when(jdbcTemplate.queryForMap(
                contains("INSERT INTO as_tickets"),
                eq(USER.internalId()),
                eq(null),
                eq("닫힌 상담 이후 새 증상입니다.")
        )).thenReturn(Map.of(
                "id", "new-ticket-public-id",
                "internal_id", 6002L
        ));
        when(jdbcTemplate.queryForList(
                contains("INSERT INTO support_chat_rooms"),
                eq(USER.internalId()),
                eq(6002L),
                eq("AS 상담방"),
                eq(SYSTEM_MESSAGE)
        )).thenReturn(List.of(Map.of(
                "internal_id", 7002L,
                "id", "new-room-public-id"
        )));
        when(jdbcTemplate.queryForList(anyString(), eq("new-ticket-public-id"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of(
                        "id", "new-ticket-public-id",
                        "user_id", USER.id(),
                        "status", "OPEN",
                        "symptom", "닫힌 상담 이후 새 증상입니다.",
                        "cause_candidates", "[]",
                        "upgrade_candidates", "[]",
                        "support_chat_room_id", "new-room-public-id"
                )));

        Map<String, Object> result = service.create(Map.of("symptom", "닫힌 상담 이후 새 증상입니다."), USER);

        assertThat(result).containsEntry("id", "new-ticket-public-id");
        verify(jdbcTemplate).queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId()));
        verify(jdbcTemplate).queryForMap(contains("INSERT INTO as_tickets"), eq(USER.internalId()), eq(null), eq("닫힌 상담 이후 새 증상입니다."));
    }

    private void mockLockedUser() {
        when(jdbcTemplate.queryForList(contains("FOR UPDATE"), eq(USER.internalId())))
                .thenReturn(List.of(Map.of("id", USER.internalId())));
    }

    private void mockNoOpenSupportChat() {
        when(jdbcTemplate.queryForList(contains("t.status NOT IN ('CLOSED', 'CANCELLED')"), eq(USER.internalId())))
                .thenReturn(List.of());
    }
}

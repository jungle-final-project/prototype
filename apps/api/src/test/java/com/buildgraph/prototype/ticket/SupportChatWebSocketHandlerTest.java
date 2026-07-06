package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class SupportChatWebSocketHandlerTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    private final SupportChatService supportChatService = mock(SupportChatService.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final SupportChatWebSocketHandler handler = new SupportChatWebSocketHandler(supportChatService, currentUserService);

    @Test
    void inboundMessageFrameIsRejectedWithoutPersistingMessage() throws Exception {
        WebSocketSession session = session("user");

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "MESSAGE",
                  "content": "지금 상담 가능할까요?"
                }
                """))).doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "WS_MESSAGE_DISABLED");
    }

    @Test
    void malformedPayloadReturnsErrorFrameWithoutThrowing() throws Exception {
        WebSocketSession session = session("user");

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("{not-json")))
                .doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "INVALID_WS_PAYLOAD");
    }

    @Test
    void unknownPayloadTypeReturnsErrorFrameWithoutThrowing() throws Exception {
        WebSocketSession session = session("admin");

        assertThatCode(() -> handler.handleTextMessage(session, new TextMessage("""
                {
                  "type": "PING"
                }
                """))).doesNotThrowAnyException();

        verify(supportChatService, never()).postUserMessage(any(), any(), any());
        verify(supportChatService, never()).postAdminMessage(any(), any(), any());
        assertSentFrame(session, "ERROR", "INVALID_WS_PAYLOAD");
    }

    @Test
    void socketOpenAndBroadcastUseUnreadSafeSnapshot() throws Exception {
        WebSocketSession session = session("user");
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/support-chat?token=jwt-token&mode=user&sessionId=" + ROOM_ID));
        when(session.isOpen()).thenReturn(true);
        when(currentUserService.requireUser("Bearer jwt-token")).thenReturn(USER);
        when(supportChatService.userCanAccess(ROOM_ID, USER)).thenReturn(true);
        when(supportChatService.detailSnapshot(ROOM_ID, USER)).thenReturn(MockData.map(
                "contact", MockData.map("id", ROOM_ID, "userUnreadCount", 2),
                "messages", List.of(),
                "pollingIntervalMs", 5000
        ));

        handler.afterConnectionEstablished(session);
        handler.broadcastRoomUpdate(ROOM_ID);

        verify(supportChatService, times(2)).detailSnapshot(ROOM_ID, USER);
        verify(supportChatService, never()).detail(ROOM_ID, USER);
    }

    private static WebSocketSession session(String mode) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("chatSessionId", "00000000-0000-4000-8000-000000009001");
        attributes.put("mode", mode);
        attributes.put("authorization", "Bearer jwt-token");
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }

    private static void assertSentFrame(WebSocketSession session, String type, String code) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(captor.capture());
        String payload = captor.getValue().getPayload();
        assertThat(payload).contains("\"type\":\"" + type + "\"");
        assertThat(payload).contains("\"code\":\"" + code + "\"");
    }
}

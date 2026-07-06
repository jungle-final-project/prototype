package com.buildgraph.prototype.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class AdminSupportChatQueueWebSocketHandlerTest {
    private static final String ROOM_ID = "00000000-0000-4000-8000-000000009001";
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000000001",
            "admin@example.com",
            "BuildGraph Admin",
            "ADMIN",
            null
    );

    private final SupportChatService supportChatService = mock(SupportChatService.class);
    private final SupportChatWebSocketTicketService ticketService = mock(SupportChatWebSocketTicketService.class);
    private final AdminSupportChatQueueWebSocketHandler handler = new AdminSupportChatQueueWebSocketHandler(
            supportChatService,
            ticketService
    );

    @Test
    void authFrameRegistersQueueSessionAndSendsReadyFrame() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession();
        when(ticketService.consume("queue-ticket")).thenReturn(Optional.of(adminQueueTicket()));

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, authFrame("queue-ticket"));

        assertThat(session.getAttributes()).containsEntry("authenticated", true);
        assertSentFrame(session, "SUPPORT_CHAT_QUEUE_READY", null);
    }

    @Test
    void invalidTicketReturnsErrorAndCloses() throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession();
        when(ticketService.consume("expired-ticket")).thenReturn(Optional.empty());

        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, authFrame("expired-ticket"));

        assertSentFrame(session, "ERROR", "INVALID_WS_TICKET");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void authenticatedQueueReceivesUpdatedContactPatch() throws Exception {
        WebSocketSession session = authenticatedConnectedSession("queue-ticket");
        when(supportChatService.adminQueueContactSnapshot(ROOM_ID)).thenReturn(Optional.of(contact(3)));

        handler.broadcastQueuePatch(ROOM_ID);

        assertSentFrame(session, "SUPPORT_CHAT_QUEUE_UPDATED", null);
        assertLastPayload(session).contains("\"adminUnreadCount\":3");
    }

    @Test
    void authenticatedQueueReceivesRemovedPatchWhenRoomLeavesAdminList() throws Exception {
        WebSocketSession session = authenticatedConnectedSession("queue-ticket");
        when(supportChatService.adminQueueContactSnapshot(ROOM_ID)).thenReturn(Optional.empty());

        handler.broadcastQueuePatch(ROOM_ID);

        assertSentFrame(session, "SUPPORT_CHAT_QUEUE_REMOVED", null);
        assertLastPayload(session).contains("\"id\":\"" + ROOM_ID + "\"");
    }

    @Test
    void broadcastKeepsOtherQueueSessionsWhenOneSendFails() throws Exception {
        WebSocketSession failingSession = authenticatedConnectedSession("ticket-failing");
        WebSocketSession healthySession = authenticatedConnectedSession("ticket-healthy");
        when(supportChatService.adminQueueContactSnapshot(ROOM_ID)).thenReturn(Optional.of(contact(5)));
        doThrow(new IOException("socket write failed")).when(failingSession).sendMessage(any(TextMessage.class));

        assertThatCode(() -> handler.broadcastQueuePatch(ROOM_ID)).doesNotThrowAnyException();

        verify(failingSession).close();
        verify(healthySession, times(2)).sendMessage(any(TextMessage.class));
    }

    private WebSocketSession authenticatedConnectedSession(String ticket) throws Exception {
        WebSocketSession session = connectedButUnauthenticatedSession();
        when(ticketService.consume(ticket)).thenReturn(Optional.of(adminQueueTicket()));
        handler.afterConnectionEstablished(session);
        handler.handleTextMessage(session, authFrame(ticket));
        return session;
    }

    private static SupportChatWebSocketTicketService.AuthenticatedTicket adminQueueTicket() {
        return new SupportChatWebSocketTicketService.AuthenticatedTicket(
                "admin-queue",
                "admin-support-chat-queue",
                ADMIN
        );
    }

    private static TextMessage authFrame(String ticket) {
        return new TextMessage("""
                {
                  "type": "AUTH",
                  "ticket": "%s"
                }
                """.formatted(ticket));
    }

    private static WebSocketSession connectedButUnauthenticatedSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        when(session.getId()).thenReturn("queue-session-" + System.nanoTime());
        when(session.getAttributes()).thenReturn(attributes);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/admin/support-chat-queue"));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static Map<String, Object> contact(int unreadCount) {
        return MockData.map(
                "id", ROOM_ID,
                "asTicketId", "00000000-0000-4000-8000-000000006001",
                "status", "ACTIVE",
                "ticketStatus", "OPEN",
                "title", "AS 상담방",
                "lastMessagePreview", "새 메시지",
                "lastMessageAt", "2026-07-06T10:10:00Z",
                "adminUnreadCount", unreadCount,
                "userUnreadCount", 0,
                "canSendMessage", true
        );
    }

    private static void assertSentFrame(WebSocketSession session, String type, String code) throws Exception {
        String payload = assertLastPayload(session);
        assertThat(payload).contains("\"type\":\"" + type + "\"");
        if (code != null) {
            assertThat(payload).contains("\"code\":\"" + code + "\"");
        }
    }

    private static String assertLastPayload(WebSocketSession session) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        return captor.getAllValues().get(captor.getAllValues().size() - 1).getPayload();
    }
}

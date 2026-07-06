package com.buildgraph.prototype.ticket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class AdminSupportChatQueueWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;
    private static final int POLLING_INTERVAL_MS = 5000;
    private static final long AUTH_TIMEOUT_MS = 5_000L;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SupportChatService supportChatService;
    private final SupportChatWebSocketTicketService ticketService;
    private final Set<SessionRegistration> sessions = ConcurrentHashMap.newKeySet();

    public AdminSupportChatQueueWebSocketHandler(
            SupportChatService supportChatService,
            SupportChatWebSocketTicketService ticketService
    ) {
        this.supportChatService = supportChatService;
        this.ticketService = ticketService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put("authenticated", false);
        scheduleAuthTimeout(session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload;
        try {
            payload = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
        } catch (Exception error) {
            sendError(session, "INVALID_WS_PAYLOAD", "잘못된 WebSocket 메시지입니다.", false);
            if (!authenticated(session)) {
                closePolicyViolation(session);
            }
            return;
        }

        Object type = payload.get("type");
        if (!authenticated(session)) {
            if ("AUTH".equals(type)) {
                authenticateSession(session, payload);
                return;
            }
            sendError(session, "WS_AUTH_REQUIRED", "WebSocket 인증이 필요합니다.", false);
            closePolicyViolation(session);
            return;
        }
        if ("MESSAGE".equals(type)) {
            sendError(session, "WS_MESSAGE_DISABLED", "관리자 상담방 목록 WebSocket은 메시지를 전송하지 않습니다.", false);
            return;
        }
        sendError(session, "INVALID_WS_PAYLOAD", "지원하지 않는 WebSocket 메시지입니다.", false);
    }

    public void broadcastQueuePatch(String roomId) {
        Optional<Map<String, Object>> contact = supportChatService.adminQueueContactSnapshot(roomId);
        for (SessionRegistration registration : sessions) {
            WebSocketSession session = registration.session();
            try {
                if (!session.isOpen()) {
                    removeSession(registration);
                    continue;
                }
                if (contact.isPresent()) {
                    send(session, Map.of(
                            "type", "SUPPORT_CHAT_QUEUE_UPDATED",
                            "contact", contact.orElseThrow()
                    ));
                } else {
                    send(session, Map.of(
                            "type", "SUPPORT_CHAT_QUEUE_REMOVED",
                            "id", roomId
                    ));
                }
            } catch (Exception error) {
                closeQuietly(session);
                removeSession(registration);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        removeSession(session);
    }

    private void authenticateSession(WebSocketSession session, Map<String, Object> payload) throws IOException {
        String ticket = first(payload.get("ticket") instanceof String value ? value : null);
        if (ticket == null) {
            rejectTicket(session);
            return;
        }
        Optional<SupportChatWebSocketTicketService.AuthenticatedTicket> authenticatedTicket = ticketService.consume(ticket);
        if (authenticatedTicket.isEmpty()) {
            rejectTicket(session);
            return;
        }
        SupportChatWebSocketTicketService.AuthenticatedTicket auth = authenticatedTicket.orElseThrow();
        if (!Objects.equals(auth.mode(), SupportChatWebSocketTicketService.ADMIN_QUEUE_MODE)
                || !Objects.equals(auth.sessionId(), SupportChatWebSocketTicketService.ADMIN_QUEUE_SESSION_ID)
                || !"ADMIN".equals(auth.user().role())) {
            rejectTicket(session);
            return;
        }
        session.getAttributes().put("authenticated", true);
        WebSocketSession outboundSession = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MS,
                SEND_BUFFER_SIZE_LIMIT_BYTES
        );
        SessionRegistration registration = new SessionRegistration(session.getId(), outboundSession);
        sessions.add(registration);
        try {
            send(outboundSession, Map.of(
                    "type", "SUPPORT_CHAT_QUEUE_READY",
                    "pollingIntervalMs", POLLING_INTERVAL_MS
            ));
        } catch (Exception error) {
            closeQuietly(outboundSession);
            removeSession(registration);
        }
    }

    private void rejectTicket(WebSocketSession session) throws IOException {
        sendError(session, "INVALID_WS_TICKET", "WebSocket 인증 티켓이 유효하지 않습니다.", false);
        closePolicyViolation(session);
    }

    private void scheduleAuthTimeout(WebSocketSession session) {
        CompletableFuture.delayedExecutor(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            try {
                if (session.isOpen() && !authenticated(session)) {
                    sendError(session, "WS_AUTH_REQUIRED", "WebSocket 인증 시간이 만료되었습니다.", false);
                    closePolicyViolation(session);
                }
            } catch (Exception ignored) {
                closePolicyViolation(session);
            }
        });
    }

    private boolean authenticated(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get("authenticated"));
    }

    private void removeSession(WebSocketSession session) {
        String sessionId = session.getId();
        sessions.removeIf(registration -> registration.session() == session
                || Objects.equals(registration.originalSessionId(), sessionId)
                || Objects.equals(registration.session().getId(), sessionId));
    }

    private void removeSession(SessionRegistration registration) {
        sessions.remove(registration);
    }

    private void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (Exception ignored) {
            // Queue socket failures are isolated to the failing session.
        }
    }

    private void closePolicyViolation(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (Exception ignored) {
            // Authentication cleanup failure is non-fatal.
        }
    }

    private void send(WebSocketSession session, Map<String, Object> payload) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(payload)));
    }

    private void sendError(WebSocketSession session, String code, String message, boolean retryable) throws IOException {
        send(session, Map.of(
                "type", "ERROR",
                "code", code,
                "message", message,
                "retryable", retryable
        ));
    }

    private static String first(String value) {
        String text = value == null ? null : value.trim();
        return text == null || text.isBlank() ? null : text;
    }

    private record SessionRegistration(String originalSessionId, WebSocketSession session) {
    }
}

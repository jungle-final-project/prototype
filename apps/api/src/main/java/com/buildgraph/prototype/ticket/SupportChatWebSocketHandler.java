package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class SupportChatWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SupportChatService supportChatService;
    private final CurrentUserService currentUserService;
    private final Map<String, Set<WebSocketSession>> sessionsByChatId = new ConcurrentHashMap<>();

    public SupportChatWebSocketHandler(SupportChatService supportChatService, CurrentUserService currentUserService) {
        this.supportChatService = supportChatService;
        this.currentUserService = currentUserService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            Handshake handshake = handshake(session);
            CurrentUserService.CurrentUser user = authenticate(handshake);
            requireAccess(handshake, user);
            session.getAttributes().put("chatSessionId", handshake.sessionId());
            session.getAttributes().put("mode", handshake.mode());
            session.getAttributes().put("authorization", "Bearer " + handshake.token());
            sessionsByChatId.computeIfAbsent(handshake.sessionId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(session);
            send(session, "CHAT_UPDATED", detail(handshake, user));
        } catch (Exception error) {
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("invalid support chat socket"));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String chatSessionId = String.valueOf(session.getAttributes().get("chatSessionId"));
        String mode = String.valueOf(session.getAttributes().get("mode"));
        String authorization = String.valueOf(session.getAttributes().get("authorization"));
        Map<String, Object> payload = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
        String content = String.valueOf(payload.getOrDefault("content", "")).trim();
        Map<String, Object> request = Map.of("content", content);
        if ("admin".equals(mode)) {
            CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
            supportChatService.postAdminMessage(chatSessionId, request, admin);
        } else {
            CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
            supportChatService.postUserMessage(chatSessionId, request, user);
        }
        broadcastRoomUpdate(chatSessionId);
    }

    public void broadcastRoomUpdate(String chatSessionId) {
        Set<WebSocketSession> sessions = sessionsByChatId.getOrDefault(chatSessionId, Set.of());
        for (WebSocketSession session : sessions) {
            try {
                if (session.isOpen()) {
                    send(session, "CHAT_UPDATED", detailFor(session, chatSessionId));
                }
            } catch (Exception error) {
                // 한 세션의 전송 실패가 REST 응답이나 다른 세션 push를 막으면 안 된다. 놓친 갱신은 fallback polling이 보완한다.
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object chatSessionId = session.getAttributes().get("chatSessionId");
        if (chatSessionId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByChatId.get(chatSessionId.toString());
        if (sessions != null) {
            sessions.remove(session);
            if (sessions.isEmpty()) {
                sessionsByChatId.remove(chatSessionId.toString());
            }
        }
    }

    private Handshake handshake(WebSocketSession session) {
        URI uri = session.getUri();
        if (uri == null) {
            throw new IllegalArgumentException("missing websocket uri");
        }
        var params = UriComponentsBuilder.fromUri(uri).build().getQueryParams();
        String token = first(params.getFirst("token"));
        String mode = first(params.getFirst("mode"));
        String sessionId = first(params.getFirst("sessionId"));
        if (!"user".equals(mode) && !"admin".equals(mode)) {
            throw new IllegalArgumentException("invalid websocket mode");
        }
        if (token == null || sessionId == null) {
            throw new IllegalArgumentException("missing websocket auth or session id");
        }
        return new Handshake(token, mode, sessionId);
    }

    private CurrentUserService.CurrentUser authenticate(Handshake handshake) {
        String authorization = "Bearer " + handshake.token();
        if ("admin".equals(handshake.mode())) {
            return currentUserService.requireAdmin(authorization);
        }
        return currentUserService.requireUser(authorization);
    }

    private void requireAccess(Handshake handshake, CurrentUserService.CurrentUser user) {
        boolean allowed = "admin".equals(handshake.mode())
                ? supportChatService.adminCanAccess(handshake.sessionId())
                : supportChatService.userCanAccess(handshake.sessionId(), user);
        if (!allowed) {
            throw new IllegalArgumentException("support chat access denied");
        }
    }

    private Map<String, Object> detail(Handshake handshake, CurrentUserService.CurrentUser user) {
        if ("admin".equals(handshake.mode())) {
            return supportChatService.adminDetail(handshake.sessionId(), user);
        }
        return supportChatService.detail(handshake.sessionId(), user);
    }

    private Map<String, Object> detailFor(WebSocketSession session, String chatSessionId) {
        String mode = String.valueOf(session.getAttributes().get("mode"));
        String authorization = String.valueOf(session.getAttributes().get("authorization"));
        if ("admin".equals(mode)) {
            return supportChatService.adminDetail(chatSessionId, currentUserService.requireAdmin(authorization));
        }
        return supportChatService.detail(chatSessionId, currentUserService.requireUser(authorization));
    }

    private void send(WebSocketSession session, String type, Map<String, Object> detail) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", type,
                "detail", detail
        ))));
    }

    private static String first(String value) {
        String text = value == null ? null : value.trim();
        return text == null || text.isBlank() ? null : text;
    }

    private record Handshake(String token, String mode, String sessionId) {
    }
}

package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.admin.AdminCustomerContactService;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class SupportChatWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final CurrentUserService currentUserService;
    private final SupportChatService supportChatService;
    private final AdminCustomerContactService adminCustomerContactService;
    private final Map<String, Set<WebSocketSession>> sessionsByChatId = new ConcurrentHashMap<>();

    public SupportChatWebSocketHandler(
            CurrentUserService currentUserService,
            SupportChatService supportChatService,
            AdminCustomerContactService adminCustomerContactService
    ) {
        this.currentUserService = currentUserService;
        this.supportChatService = supportChatService;
        this.adminCustomerContactService = adminCustomerContactService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        try {
            Map<String, String> params = queryParams(session.getUri());
            CurrentUserService.CurrentUser user = currentUserService.requireUserByAccessToken(params.get("token"));
            String mode = params.getOrDefault("mode", "user");
            String sessionId = params.get("sessionId");
            if ("admin".equals(mode)) {
                requireAdmin(user);
                if (sessionId == null || sessionId.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "sessionId 값이 필요합니다.");
                }
                adminCustomerContactService.contact(sessionId);
            } else {
                mode = "user";
                if (sessionId == null || sessionId.isBlank()) {
                    Map<String, Object> current = supportChatService.current(user);
                    Object contact = current.get("contact");
                    if (!(contact instanceof Map<?, ?> contactMap) || contactMap.get("id") == null) {
                        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "활성 상담방이 없습니다.");
                    }
                    sessionId = String.valueOf(contactMap.get("id"));
                } else {
                    supportChatService.contact(sessionId, user);
                }
            }

            session.getAttributes().put("mode", mode);
            session.getAttributes().put("user", user);
            session.getAttributes().put("sessionId", sessionId);
            sessionsByChatId.computeIfAbsent(sessionId, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            sendDetail(session);
        } catch (ResponseStatusException exception) {
            send(session, envelope("ERROR", Map.of(
                    "status", exception.getStatusCode().value(),
                    "message", exception.getReason() == null ? "WebSocket 인증에 실패했습니다." : exception.getReason()
            )));
            session.close(closeStatus(exception));
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
        String type = string(payload.get("type"), "MESSAGE");
        if (!"MESSAGE".equals(type)) {
            return;
        }
        String content = string(payload.get("content"), "").trim();
        if (content.isBlank()) {
            send(session, envelope("ERROR", Map.of("status", 400, "message", "content 값이 필요합니다.")));
            return;
        }
        String sessionId = String.valueOf(session.getAttributes().get("sessionId"));
        String mode = String.valueOf(session.getAttributes().get("mode"));
        CurrentUserService.CurrentUser user = (CurrentUserService.CurrentUser) session.getAttributes().get("user");

        try {
            if ("admin".equals(mode)) {
                requireAdmin(user);
                adminCustomerContactService.postMessage(sessionId, Map.of("content", content), user);
            } else {
                supportChatService.postMessage(sessionId, Map.of("content", content), user);
            }
            broadcastDetail(sessionId);
        } catch (ResponseStatusException exception) {
            send(session, envelope("ERROR", Map.of(
                    "status", exception.getStatusCode().value(),
                    "message", exception.getReason() == null ? "메시지 전송에 실패했습니다." : exception.getReason()
            )));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object sessionId = session.getAttributes().get("sessionId");
        if (sessionId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByChatId.get(String.valueOf(sessionId));
        if (sessions == null) {
            return;
        }
        sessions.remove(session);
        if (sessions.isEmpty()) {
            sessionsByChatId.remove(String.valueOf(sessionId));
        }
    }

    private void broadcastDetail(String sessionId) {
        Set<WebSocketSession> sessions = sessionsByChatId.getOrDefault(sessionId, Set.of());
        List<WebSocketSession> snapshot = List.copyOf(sessions);
        for (WebSocketSession session : snapshot) {
            if (session.isOpen()) {
                sendDetail(session);
            }
        }
    }

    private void sendDetail(WebSocketSession session) {
        String sessionId = String.valueOf(session.getAttributes().get("sessionId"));
        String mode = String.valueOf(session.getAttributes().get("mode"));
        CurrentUserService.CurrentUser user = (CurrentUserService.CurrentUser) session.getAttributes().get("user");
        Map<String, Object> detail = "admin".equals(mode)
                ? adminCustomerContactService.contact(sessionId)
                : supportChatService.contact(sessionId, user);
        send(session, envelope("CHAT_DETAIL", detail));
        if ("admin".equals(mode)) {
            send(session, envelope("CONTACTS_INVALIDATED", Map.of("sessionId", sessionId)));
        }
    }

    private static void send(WebSocketSession session, Map<String, Object> payload) {
        if (!session.isOpen()) {
            return;
        }
        try {
            session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(payload)));
        } catch (IOException ignored) {
        }
    }

    private static Map<String, Object> envelope(String type, Object payload) {
        return Map.of("type", type, "payload", payload);
    }

    private static void requireAdmin(CurrentUserService.CurrentUser user) {
        if (!"ADMIN".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
    }

    private static CloseStatus closeStatus(ResponseStatusException exception) {
        int status = exception.getStatusCode().value();
        if (status == 401 || status == 403) {
            return CloseStatus.POLICY_VIOLATION;
        }
        return CloseStatus.BAD_DATA;
    }

    private static Map<String, String> queryParams(URI uri) {
        if (uri == null || uri.getRawQuery() == null || uri.getRawQuery().isBlank()) {
            return Collections.emptyMap();
        }
        Map<String, String> params = new ConcurrentHashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            int index = part.indexOf('=');
            if (index < 0) {
                params.put(decode(part), "");
            } else {
                params.put(decode(part.substring(0, index)), decode(part.substring(index + 1)));
            }
        }
        return params;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static String string(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}

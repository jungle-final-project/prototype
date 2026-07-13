package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationResult;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class PcAgentDiagnosisWebSocketHandler extends TextWebSocketHandler {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final long AUTH_TIMEOUT_MS = 5_000L;

    private final AgentTokenAuthenticationService authenticationService;
    private final PcAgentDiagnosisSocketBroker broker;

    public PcAgentDiagnosisWebSocketHandler(
            AgentTokenAuthenticationService authenticationService,
            PcAgentDiagnosisSocketBroker broker
    ) {
        this.authenticationService = authenticationService;
        this.broker = broker;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        session.getAttributes().put("authenticated", false);
        CompletableFuture.delayedExecutor(AUTH_TIMEOUT_MS, TimeUnit.MILLISECONDS).execute(() -> {
            if (session.isOpen() && !authenticated(session)) {
                sendErrorQuietly(session, "AUTH_FAILED", "Agent 인증 시간이 만료되었습니다.");
                closePolicyViolation(session);
            }
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        Map<String, Object> payload;
        try {
            payload = OBJECT_MAPPER.readValue(message.getPayload(), MAP_TYPE);
        } catch (Exception error) {
            sendError(session, "INVALID_WS_PAYLOAD", "잘못된 WebSocket 메시지입니다.");
            if (!authenticated(session)) {
                closePolicyViolation(session);
            }
            return;
        }
        if (!authenticated(session)) {
            if ("AUTH".equals(payload.get("type"))) {
                authenticate(session, payload);
                return;
            }
            sendError(session, "AUTH_FAILED", "Agent 인증이 필요합니다.");
            closePolicyViolation(session);
            return;
        }
        if ("DIAGNOSIS_RESPONSE".equals(payload.get("type"))) {
            recordDiagnosisResponse(session, payload);
            return;
        }
        sendError(session, "INVALID_WS_PAYLOAD", "지원하지 않는 WebSocket 메시지입니다.");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object principal = session.getAttributes().get("agentPrincipal");
        if (principal instanceof AgentPrincipal agentPrincipal) {
            broker.unregister(agentPrincipal.deviceId(), session);
        }
    }

    private void authenticate(WebSocketSession session, Map<String, Object> payload) throws IOException {
        String agentToken = text(payload.get("agentToken"));
        if (agentToken == null) {
            rejectAuthentication(session, "AUTH_FAILED", "Agent 토큰이 필요합니다.");
            return;
        }
        AgentTokenAuthenticationResult result = authenticationService.authenticate(agentToken);
        if (result.status() != AgentTokenAuthenticationResult.Status.AUTHENTICATED) {
            String code = result.status() == AgentTokenAuthenticationResult.Status.FORBIDDEN
                    ? "AGENT_FORBIDDEN"
                    : "AUTH_FAILED";
            rejectAuthentication(session, code, result.message());
            return;
        }
        AgentPrincipal principal = result.principal().orElseThrow();
        session.getAttributes().put("authenticated", true);
        session.getAttributes().put("agentPrincipal", principal);
        WebSocketSession outbound = broker.register(principal, session);
        session.getAttributes().put("outboundSession", outbound);
        send(outbound, "READY", Map.of(
                "deviceId", principal.deviceId(),
                "agentState", "IDLE"
        ));
    }

    private void recordDiagnosisResponse(WebSocketSession session, Map<String, Object> payload) throws IOException {
        AgentPrincipal principal = (AgentPrincipal) session.getAttributes().get("agentPrincipal");
        String diagnosisId = text(payload.get("diagnosisId"));
        String status = text(payload.get("status"));
        String message = text(payload.get("message"));
        if (diagnosisId == null || status == null
                || !broker.recordResponse(principal.deviceId(), diagnosisId, status, message)) {
            sendError(outbound(session), "INVALID_DIAGNOSIS_RESPONSE", "진단 응답이 현재 요청과 일치하지 않습니다.");
        }
    }

    private void rejectAuthentication(WebSocketSession session, String code, String message) throws IOException {
        sendError(session, code, message == null ? "Agent 인증에 실패했습니다." : message);
        closePolicyViolation(session);
    }

    private static boolean authenticated(WebSocketSession session) {
        return Boolean.TRUE.equals(session.getAttributes().get("authenticated"));
    }

    private static WebSocketSession outbound(WebSocketSession session) {
        Object outbound = session.getAttributes().get("outboundSession");
        return outbound instanceof WebSocketSession socket ? socket : session;
    }

    private static void send(WebSocketSession session, String type, Map<String, Object> detail) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", type,
                "detail", detail
        ))));
    }

    private static void sendError(WebSocketSession session, String code, String message) throws IOException {
        session.sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                "type", "ERROR",
                "code", code,
                "message", message,
                "retryable", false
        ))));
    }

    private static void sendErrorQuietly(WebSocketSession session, String code, String message) {
        try {
            sendError(session, code, message);
        } catch (IOException ignored) {
            // 인증 타임아웃 정리 중 전송 실패는 close로 마무리한다.
        }
    }

    private static void closePolicyViolation(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close(CloseStatus.POLICY_VIOLATION);
            }
        } catch (IOException ignored) {
            // 연결 종료 중 실패는 컨테이너 정리 경로에 맡긴다.
        }
    }

    private static String text(Object value) {
        String result = value instanceof String text ? text.trim() : null;
        return result == null || result.isBlank() ? null : result;
    }
}

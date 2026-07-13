package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

@Component
public class PcAgentDiagnosisSocketBroker {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int SEND_TIME_LIMIT_MS = 10_000;
    private static final int SEND_BUFFER_SIZE_LIMIT_BYTES = 64 * 1024;
    private static final Duration RESPONSE_TIMEOUT = Duration.ofSeconds(5);
    private static final Set<String> RESPONSE_STATUSES = Set.of(
            "ACCEPTED",
            "DUPLICATE",
            "EXPIRED",
            "DEVICE_MISMATCH",
            "AUTH_FAILED",
            "BUSY",
            "REJECTED"
    );
    private static final Set<String> DIAGNOSIS_STATES = Set.of(
            "RECEIVED",
            "COLLECTING",
            "DIAGNOSING",
            "EVALUATING",
            "COMPLETED",
            "PARTIALLY_COMPLETED",
            "FAILED",
            "CANCELLED",
            "TIMED_OUT"
    );

    private final Map<String, SessionRegistration> sessionsByDeviceId = new ConcurrentHashMap<>();
    private final Map<String, PendingResponse> pendingByDiagnosisId = new ConcurrentHashMap<>();
    private final Map<String, DiagnosisStatus> latestStatusByDiagnosisId = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> statusEventIdsByDiagnosisId = new ConcurrentHashMap<>();

    public WebSocketSession register(AgentPrincipal principal, WebSocketSession session) {
        WebSocketSession outbound = new ConcurrentWebSocketSessionDecorator(
                session,
                SEND_TIME_LIMIT_MS,
                SEND_BUFFER_SIZE_LIMIT_BYTES
        );
        SessionRegistration next = new SessionRegistration(session.getId(), outbound);
        SessionRegistration previous = sessionsByDeviceId.put(principal.deviceId(), next);
        if (previous != null && !Objects.equals(previous.originalSessionId(), session.getId())) {
            closeQuietly(previous.session());
        }
        return outbound;
    }

    public void unregister(String deviceId, WebSocketSession session) {
        if (deviceId == null) {
            return;
        }
        sessionsByDeviceId.computeIfPresent(deviceId, (ignored, registration) ->
                matches(registration, session) ? null : registration);
    }

    public boolean isConnected(String deviceId) {
        SessionRegistration registration = sessionsByDeviceId.get(deviceId);
        if (registration == null || !registration.session().isOpen()) {
            if (registration != null) {
                sessionsByDeviceId.remove(deviceId, registration);
            }
            return false;
        }
        return true;
    }

    public AgentResponse dispatchAndAwait(PcAgentDiagnosisRequest request) {
        SessionRegistration registration = sessionsByDeviceId.get(request.deviceId());
        if (registration == null || !registration.session().isOpen()) {
            throw new ApiException(HttpStatus.CONFLICT, "AGENT_DISCONNECTED", "연결된 PC Agent가 없습니다.");
        }
        PendingResponse pending = new PendingResponse(request.deviceId(), new CompletableFuture<>());
        if (pendingByDiagnosisId.putIfAbsent(request.diagnosisId(), pending) != null) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_DIAGNOSIS_ID", "이미 전송 중인 진단 요청입니다.");
        }
        try {
            registration.session().sendMessage(new TextMessage(OBJECT_MAPPER.writeValueAsString(Map.of(
                    "type", "DIAGNOSIS_REQUEST",
                    "detail", request.toMap()
            ))));
            return pending.future().get(RESPONSE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "AGENT_RESPONSE_TIMEOUT", "PC Agent 응답 시간이 초과되었습니다.");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_RESPONSE_INTERRUPTED", "PC Agent 응답 대기가 중단되었습니다.");
        } catch (IOException error) {
            sessionsByDeviceId.remove(request.deviceId(), registration);
            closeQuietly(registration.session());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_CONNECTION_FAILED", "PC Agent 요청 전송에 실패했습니다.");
        } catch (java.util.concurrent.ExecutionException error) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AGENT_RESPONSE_FAILED", "PC Agent 응답 처리에 실패했습니다.");
        } finally {
            pendingByDiagnosisId.remove(request.diagnosisId(), pending);
        }
    }

    public boolean recordResponse(String deviceId, String diagnosisId, String status, String message) {
        if (!RESPONSE_STATUSES.contains(status)) {
            return false;
        }
        PendingResponse pending = pendingByDiagnosisId.get(diagnosisId);
        if (pending == null || !Objects.equals(deviceId, pending.deviceId())) {
            return false;
        }
        return pending.future().complete(new AgentResponse(status, message));
    }

    public boolean recordStatus(
            String deviceId,
            String diagnosisId,
            String eventId,
            String eventType,
            String sessionState,
            int progress,
            String message,
            Map<String, Object> metadata
    ) {
        if (deviceId == null || diagnosisId == null || diagnosisId.isBlank()
                || eventId == null || eventId.isBlank()
                || eventType == null || eventType.isBlank()
                || !DIAGNOSIS_STATES.contains(sessionState)
                || progress < 0 || progress > 100) {
            return false;
        }
        Set<String> eventIds = statusEventIdsByDiagnosisId.computeIfAbsent(
                diagnosisId,
                ignored -> ConcurrentHashMap.newKeySet()
        );
        if (!eventIds.add(eventId)) {
            return true;
        }
        latestStatusByDiagnosisId.put(diagnosisId, new DiagnosisStatus(
                deviceId,
                diagnosisId,
                eventId,
                eventType,
                sessionState,
                progress,
                message,
                metadata == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(metadata))
        ));
        return true;
    }

    DiagnosisStatus latestStatus(String diagnosisId) {
        return latestStatusByDiagnosisId.get(diagnosisId);
    }

    private static boolean matches(SessionRegistration registration, WebSocketSession session) {
        return registration.session() == session
                || Objects.equals(registration.originalSessionId(), session.getId())
                || Objects.equals(registration.session().getId(), session.getId());
    }

    private static void closeQuietly(WebSocketSession session) {
        try {
            if (session.isOpen()) {
                session.close();
            }
        } catch (IOException ignored) {
            // 재연결이 오래된 세션을 대체하므로 close 실패는 다음 정리 경로에 맡긴다.
        }
    }

    public record AgentResponse(String status, String message) {
    }

    record DiagnosisStatus(
            String deviceId,
            String diagnosisId,
            String eventId,
            String eventType,
            String sessionState,
            int progress,
            String message,
            Map<String, Object> metadata
    ) {
    }

    private record PendingResponse(String deviceId, CompletableFuture<AgentResponse> future) {
    }

    private record SessionRegistration(String originalSessionId, WebSocketSession session) {
    }
}

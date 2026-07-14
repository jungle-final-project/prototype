package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class PcAgentDiagnosisSocketBrokerTest {
    @Test
    void dispatchWaitsForMatchingAgentResponse() throws Exception {
        PcAgentDiagnosisSocketBroker broker = new PcAgentDiagnosisSocketBroker();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            TextMessage message = invocation.getArgument(0);
            assertThat(message.getPayload()).contains("\"type\":\"DIAGNOSIS_REQUEST\"");
            broker.recordResponse("device-1", "diagnosis-1", "ACCEPTED", "수신 완료");
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        broker.register(new AgentPrincipal(1L, "device-1", 7L, "ACTIVE"), session);

        PcAgentDiagnosisRequest request = new PcAgentDiagnosisRequest(
                "diagnosis-1",
                "device-1",
                "게임 실행 후 프레임 저하",
                List.of("gpu"),
                Instant.parse("2026-07-13T01:00:00Z"),
                Instant.parse("2026-07-13T01:02:00Z"),
                "LIVE"
        );
        PcAgentDiagnosisSocketBroker.AgentResponse response = broker.dispatchAndAwait(request);

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.message()).isEqualTo("수신 완료");
        assertThat(broker.latestRequest("diagnosis-1")).isSameAs(request);
        assertThat(broker.latestRequest("diagnosis-1").mode()).isEqualTo("LIVE");
    }

    @Test
    void responseFromDifferentDeviceCannotCompleteRequest() {
        PcAgentDiagnosisSocketBroker broker = new PcAgentDiagnosisSocketBroker();
        assertThat(broker.recordResponse("other-device", "missing", "ACCEPTED", "wrong"))
                .isFalse();
    }

    @Test
    void diagnosisStatusDeduplicatesEventIdAndKeepsFirstRecordedState() {
        PcAgentDiagnosisSocketBroker broker = new PcAgentDiagnosisSocketBroker();

        assertThat(broker.recordStatus(
                "device-1", "diagnosis-1", "event-1", "PROGRESS_UPDATED",
                "DIAGNOSING", 25, "진행 중", Map.of("progress", 25)
        )).isTrue();
        assertThat(broker.recordStatus(
                "device-1", "diagnosis-1", "event-1", "PROGRESS_UPDATED",
                "COMPLETED", 100, "중복", Map.of("progress", 100)
        )).isTrue();

        assertThat(broker.latestStatus("diagnosis-1").sessionState()).isEqualTo("DIAGNOSING");
        assertThat(broker.latestStatus("diagnosis-1").progress()).isEqualTo(25);
        assertThat(broker.recordStatus(
                "device-1", "diagnosis-1", "event-2", "PROGRESS_UPDATED",
                "UNKNOWN", 30, "잘못된 상태", Map.of()
        )).isFalse();
    }

    @Test
    void diagnosisResultDeduplicatesResultIdAndKeepsRawEvidenceSeparate() {
        PcAgentDiagnosisSocketBroker broker = new PcAgentDiagnosisSocketBroker();
        Map<String, Object> result = Map.of(
                "diagnosisId", "diagnosis-1",
                "resultId", "result-1",
                "severity", "CRITICAL",
                "resolutionType", "PHYSICAL_INSPECTION",
                "evaluatedAt", "2026-07-13T01:00:00Z",
                "summary", "표시용 요약",
                "evidence", List.of(Map.of("metricType", "temperature", "value", 90))
        );

        assertThat(broker.recordResult("device-1", result)).isTrue();
        assertThat(broker.recordResult("device-1", result)).isTrue();

        assertThat(broker.latestResult("diagnosis-1").resultId()).isEqualTo("result-1");
        assertThat(broker.latestResult("diagnosis-1").result().get("summary")).isEqualTo("표시용 요약");
        assertThat(broker.recordResult("device-1", Map.of(
                "diagnosisId", "diagnosis-2",
                "resultId", "result-2",
                "severity", "UNKNOWN",
                "resolutionType", "NONE",
                "evaluatedAt", "2026-07-13T01:00:00Z",
                "evidence", List.of()
        ))).isFalse();
    }
}

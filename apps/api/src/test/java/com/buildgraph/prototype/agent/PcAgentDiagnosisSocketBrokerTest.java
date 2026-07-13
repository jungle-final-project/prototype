package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.time.Instant;
import java.util.List;
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

        PcAgentDiagnosisSocketBroker.AgentResponse response = broker.dispatchAndAwait(new PcAgentDiagnosisRequest(
                "diagnosis-1",
                "device-1",
                "게임 실행 후 프레임 저하",
                List.of("gpu"),
                Instant.parse("2026-07-13T01:00:00Z"),
                Instant.parse("2026-07-13T01:02:00Z"),
                "LIVE"
        ));

        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.message()).isEqualTo("수신 완료");
    }

    @Test
    void responseFromDifferentDeviceCannotCompleteRequest() {
        PcAgentDiagnosisSocketBroker broker = new PcAgentDiagnosisSocketBroker();
        assertThat(broker.recordResponse("other-device", "missing", "ACCEPTED", "wrong"))
                .isFalse();
    }
}

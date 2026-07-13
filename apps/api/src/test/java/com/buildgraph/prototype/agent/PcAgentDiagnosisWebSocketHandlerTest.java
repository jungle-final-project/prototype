package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.config.security.AgentPrincipal;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationResult;
import com.buildgraph.prototype.config.security.AgentTokenAuthenticationService;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

class PcAgentDiagnosisWebSocketHandlerTest {
    private final AgentTokenAuthenticationService authenticationService = mock(AgentTokenAuthenticationService.class);
    private final PcAgentDiagnosisSocketBroker broker = mock(PcAgentDiagnosisSocketBroker.class);
    private final PcAgentDiagnosisWebSocketHandler handler = new PcAgentDiagnosisWebSocketHandler(
            authenticationService, broker
    );

    @Test
    void invalidAgentTokenReturnsAuthFailureAndClosesConnection() throws Exception {
        WebSocketSession session = session();
        when(authenticationService.authenticate("bad-token")).thenReturn(AgentTokenAuthenticationResult.invalid());
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"bad-token"}
                """));

        assertLastFrameContains(session, "\"code\":\"AUTH_FAILED\"");
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void validAgentTokenRegistersDeviceAndReturnsReadyFrame() throws Exception {
        WebSocketSession session = session();
        AgentPrincipal principal = new AgentPrincipal(1L, "device-1", 7L, "ACTIVE");
        when(authenticationService.authenticate("agent-token"))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(broker.register(principal, session)).thenReturn(session);
        handler.afterConnectionEstablished(session);

        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"agent-token"}
                """));

        assertThat(session.getAttributes()).containsEntry("authenticated", true);
        assertLastFrameContains(session, "\"type\":\"READY\"");
        assertLastFrameContains(session, "\"deviceId\":\"device-1\"");
    }

    @Test
    void authenticatedDiagnosisStatusIsRecordedAndAcknowledged() throws Exception {
        WebSocketSession session = session();
        AgentPrincipal principal = new AgentPrincipal(1L, "device-1", 7L, "ACTIVE");
        when(authenticationService.authenticate("agent-token"))
                .thenReturn(AgentTokenAuthenticationResult.authenticated(principal));
        when(broker.register(principal, session)).thenReturn(session);
        when(broker.recordStatus(any(), any(), any(), any(), any(), anyInt(), any(), any()))
                .thenReturn(true);
        handler.handleTextMessage(session, new TextMessage("""
                {"type":"AUTH","agentToken":"agent-token"}
                """));

        handler.handleTextMessage(session, new TextMessage("""
                {
                  "type":"DIAGNOSIS_STATUS",
                  "detail":{
                    "diagnosisId":"diagnosis-1",
                    "eventId":"event-1",
                    "eventType":"PROGRESS_UPDATED",
                    "sessionState":"DIAGNOSING",
                    "progress":25,
                    "message":"진단 진행률이 25%로 업데이트되었습니다.",
                    "metadata":{"progress":25}
                  }
                }
                """));

        assertLastFrameContains(session, "\"type\":\"DIAGNOSIS_STATUS_ACK\"");
        assertLastFrameContains(session, "\"eventId\":\"event-1\"");
    }

    private static WebSocketSession session() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("socket-1");
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private static void assertLastFrameContains(WebSocketSession session, String expected) throws Exception {
        ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
        verify(session, org.mockito.Mockito.atLeastOnce()).sendMessage(captor.capture());
        assertThat(captor.getAllValues().get(captor.getAllValues().size() - 1).getPayload()).contains(expected);
    }
}

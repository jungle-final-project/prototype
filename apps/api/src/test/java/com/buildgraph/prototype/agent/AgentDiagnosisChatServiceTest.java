package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentDiagnosisChatServiceTest {
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");

    private final AgentDiagnosisChatService service = new AgentDiagnosisChatService();

    @Test
    void replyUsesDiagnosisContextWithoutCreatingTicketShape() {
        Map<String, Object> response = service.reply(AGENT, MockData.map(
                "message", "AS 접수해야 해?",
                "diagnosis", MockData.map(
                        "summary", "그래픽 드라이버 오류가 반복됩니다.",
                        "recommendedService", "REMOTE_SUPPORT",
                        "recommendedDecision", "REMOTE_POSSIBLE",
                        "confidence", "HIGH",
                        "causeCandidates", List.of(MockData.map(
                                "label", "그래픽 드라이버 오류 가능성"
                        ))
                ),
                "messages", List.of(MockData.map(
                        "role", "user",
                        "content", "게임이 자꾸 꺼져"
                ))
        ));

        assertThat(response)
                .containsEntry("model", "buildgraph-agent-diagnosis-rule-v1")
                .doesNotContainKeys("sessionId", "asTicketId", "ticketId", "logUploadId");
        assertThat(response.get("assistantMessage")).asString().contains("티켓을 만들지 않");
        assertThat(escalation(response))
                .containsEntry("required", false)
                .containsEntry("recommended", true);
        assertThat((List<?>) response.get("nextActions")).isNotEmpty();
    }

    @Test
    void replyMarksVisitSignalsAsRequiredEscalation() {
        Map<String, Object> response = service.reply(AGENT, MockData.map(
                "message", "위험한 상태야?",
                "diagnosis", MockData.map(
                        "summary", "WHEA 오류와 Kernel-Power 이벤트가 반복됩니다.",
                        "recommendedService", "VISIT_SUPPORT",
                        "recommendedDecision", "VISIT_REQUIRED",
                        "confidence", "HIGH"
                )
        ));

        assertThat(escalation(response)).containsEntry("required", true);
        assertThat(response.get("assistantMessage")).asString().contains("주의가 필요");
    }

    @Test
    void replyRejectsMissingMessage() {
        assertThatThrownBy(() -> service.reply(AGENT, Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("VALIDATION_ERROR"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> escalation(Map<String, Object> response) {
        return (Map<String, Object>) response.get("escalation");
    }
}

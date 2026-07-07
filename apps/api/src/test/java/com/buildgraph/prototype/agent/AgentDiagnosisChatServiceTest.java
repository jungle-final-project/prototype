package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AgentDiagnosisChatServiceTest {
    private static final AgentPrincipal AGENT = new AgentPrincipal(10L, "device-public-id", 20L, "ACTIVE");

    private final AgentDiagnosisChatService service = new AgentDiagnosisChatService(prompt -> Optional.empty());

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
    void replyUsesLlmPayloadWhenAvailable() {
        AgentDiagnosisChatService llmService = new AgentDiagnosisChatService(prompt -> Optional.of(
                new AgentDiagnosisChatLlmClient.Result(MockData.map(
                        "assistantMessage", "최근 진단과 이전 질문을 보면 그래픽 드라이버 쪽부터 확인하는 게 좋습니다.",
                        "causeCandidates", List.of(MockData.map(
                                "label", "그래픽 드라이버 충돌",
                                "confidence", "HIGH",
                                "reason", "진단 요약과 사용자 질문이 드라이버 오류에 집중되어 있습니다.",
                                "evidenceIds", List.of(),
                                "toolInvocationIds", List.of()
                        )),
                        "nextActions", List.of(MockData.map(
                                "label", "그래픽 드라이버 클린 설치",
                                "priority", "HIGH",
                                "instruction", "제조사 드라이버를 내려받아 기존 드라이버를 제거한 뒤 재설치하세요.",
                                "evidenceIds", List.of(),
                                "toolInvocationIds", List.of()
                        )),
                        "escalation", MockData.map(
                                "required", false,
                                "recommended", true,
                                "reason", "원격 확인으로 먼저 조치할 수 있습니다."
                        ),
                        "ticketDraft", MockData.map(
                                "symptomSummary", "게임 중 그래픽 드라이버 오류 반복",
                                "recommendedLogRequest", "증상 직후 PC 진단을 다시 실행하세요."
                        )
                ), "gpt-diagnosis-test", 1234L)
        ));

        Map<String, Object> response = llmService.reply(AGENT, MockData.map(
                "message", "그럼 뭘 먼저 하면 돼?",
                "diagnosis", MockData.map(
                        "summary", "그래픽 드라이버 오류가 반복됩니다.",
                        "recommendedService", "REMOTE_SUPPORT",
                        "recommendedDecision", "REMOTE_POSSIBLE",
                        "confidence", "HIGH"
                ),
                "messages", List.of(MockData.map(
                        "role", "user",
                        "content", "게임이 자꾸 꺼져"
                ))
        ));

        assertThat(response)
                .containsEntry("model", "gpt-diagnosis-test")
                .doesNotContainKeys("sessionId", "asTicketId", "ticketId", "logUploadId");
        assertThat(response.get("assistantMessage")).asString().contains("그래픽 드라이버");
        assertThat((List<?>) response.get("nextActions")).hasSize(1);
        assertThat(escalation(response)).containsEntry("recommended", true);
    }

    @Test
    void replyFallsBackToRulesWhenLlmFails() {
        AgentDiagnosisChatService resilientService = new AgentDiagnosisChatService(prompt -> {
            throw new RuntimeException("upstream unavailable");
        });

        Map<String, Object> response = resilientService.reply(AGENT, MockData.map(
                "message", "원인이 뭐야?",
                "diagnosis", MockData.map(
                        "summary", "온도 상승 신호가 있습니다.",
                        "confidence", "MEDIUM"
                )
        ));

        assertThat(response)
                .containsEntry("model", "buildgraph-agent-diagnosis-rule-v1")
                .doesNotContainKeys("sessionId", "asTicketId", "ticketId", "logUploadId");
        assertThat(response.get("assistantMessage")).asString().contains("가장 가능성이 높은 원인");
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

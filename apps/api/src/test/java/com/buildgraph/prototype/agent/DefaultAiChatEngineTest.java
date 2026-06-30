package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class DefaultAiChatEngineTest {
    private JdbcTemplate jdbcTemplate;
    private AgentTraceService agentTraceService;
    private AgentRagRetrievalService agentRagRetrievalService;
    private OpenAiResponsesClient openAiResponsesClient;
    private DefaultAiChatEngine engine;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        agentTraceService = mock(AgentTraceService.class);
        agentRagRetrievalService = mock(AgentRagRetrievalService.class);
        openAiResponsesClient = mock(OpenAiResponsesClient.class);
        engine = new DefaultAiChatEngine(
                jdbcTemplate,
                agentTraceService,
                agentRagRetrievalService,
                openAiResponsesClient
        );

        doAnswer(invocation -> {
                    Object category = invocation.getArgument(1);
                    return partRows(String.valueOf(category));
                })
                .when(jdbcTemplate)
                .queryForList(anyString(), anyString(), anyInt());
    }

    @Test
    void fullBuildRecommendationReturnsThreeBuildsAndDraftActions() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "200만원 QHD 게임용 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .contains(AiChatActionType.OPEN_SELF_QUOTE, AiChatActionType.ADD_BUILD_TO_DRAFT);
        verifyNoJdbcWrites();
    }

    @Test
    void explicitRtx5090BuildKeepsGpuClassAsHardConstraint() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "RTX 5090 글카가 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext().get("requiredGpuClasses")).asList().contains("RTX_5090");
        assertThat(response.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "GPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.attributes()).containsEntry("gpuClass", "RTX_5090")));
        verifyNoJdbcWrites();
    }

    @Test
    void openBudgetEnthusiastRequestDoesNotCreateDefaultBudget() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "끝판왕 컴퓨터 만들어줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("performanceTier", "ENTHUSIAST")
                .containsEntry("budgetPolicy", "OPEN_BUDGET")
                .containsEntry("budget", null);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "GPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.partId()).isEqualTo("gpu-5090")));
        verifyNoJdbcWrites();
    }

    @Test
    void performanceRequestWithoutBudgetStaysUnspecifiedInsteadOfDefaultBudget() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "QHD 배그 144Hz 목표로 맞춰줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.parsedContext())
                .containsEntry("performanceTier", "PERFORMANCE")
                .containsEntry("budgetPolicy", "UNSPECIFIED")
                .containsEntry("resolution", "QHD")
                .containsEntry("budget", null);
        assertThat(response.parsedContext().get("usageTags")).asList().contains("GAMING");
        verifyNoJdbcWrites();
    }

    @Test
    void minimumBudgetRequestDoesNotReturnValueBuildBelowRequestedFloor() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "300만원 이상으로 게임용 PC 맞춰줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations().get(0).name()).contains("기준 이상");
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.estimatedTotalPrice()).isGreaterThanOrEqualTo(3_000_000));
        verifyNoJdbcWrites();
    }

    @Test
    void partRecommendationReturnsPartCandidatesAndAddPartActions() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "RTX 5070 중에 뭐가 좋아?",
                "SELF_QUOTE",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PART_RECOMMEND);
        assertThat(response.partRecommendations()).hasSize(3);
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.category()).isEqualTo("GPU"));
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsOnly(AiChatActionType.ADD_PART_TO_DRAFT);
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyReturnsReplaceDraftPartAction() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "이 견적에서 램 64기가로 바꿔줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.REPLACE_DRAFT_PART);
        assertThat(response.actions().get(0).payload()).containsEntry("category", "RAM");
        verifyNoJdbcWrites();
    }

    @Test
    void buildModifyCheaperGpuUsesCurrentDraftPriceAsCeiling() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "그래픽카드 너무 비싼데 더 싼 후보 추천해줘",
                "SELF_QUOTE",
                null,
                null,
                "draft-1",
                Map.of("currentQuoteDraft", Map.of(
                        "items", List.of(Map.of(
                                "partId", "gpu-5090",
                                "category", "GPU",
                                "name", "GeForce RTX 5090 32GB",
                                "currentPrice", 5_000_000,
                                "quantity", 1
                        ))
                )),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.BUILD_MODIFY);
        assertThat(response.parsedContext()).containsEntry("category", "GPU");
        assertThat(response.parsedContext().get("draftEdit"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("priceDirection", "CHEAPER");
        assertThat(response.partRecommendations())
                .extracting(AiChatEngineResponse.PartRecommendation::partId)
                .containsExactly("gpu-5080", "gpu-5070");
        assertThat(response.partRecommendations())
                .allSatisfy(part -> assertThat(part.price()).isLessThan(5_000_000));
        verifyNoJdbcWrites();
    }

    @Test
    void priceAlertHelpExtractsTargetPriceAction() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "이 GPU 80만원 되면 알려줘",
                "PART_DETAIL",
                "GPU",
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.PRICE_ALERT_HELP);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.CREATE_PRICE_ALERT);
        assertThat(response.actions().get(0).payload()).containsEntry("targetPrice", 800_000);
        verifyNoJdbcWrites();
    }

    @Test
    void vagueMessageAsksFollowUpInsteadOfGuessingRecommendation() {
        AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                "추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.ASK_FOLLOW_UP);
        assertThat(response.actions())
                .extracting(AiChatAction::type)
                .containsExactly(AiChatActionType.ASK_FOLLOW_UP);
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildChatFailsWhenOpenAiKeyIsMissing() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> engine.respondLlmRequired(new AiChatEngineRequest(
                "200만원 QHD 게임용 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.PRECONDITION_REQUIRED);
        verifyNoJdbcWrites();
    }

    @Test
    void llmRequiredBuildChatUsesStructuredPlanAndKeepsExplicitRtx5090Constraint() {
        when(openAiResponsesClient.isConfigured()).thenReturn(true);
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse()), anyString(), anyInt()))
                .thenReturn(List.of(new AgentRagEvidenceDraft(
                        "requirement-rule-explicit-gpu-class-hard-constraint",
                        "Explicit RTX 5090 should be a hard constraint.",
                        "Explicit GPU class hard constraint parse rule.",
                        BigDecimal.valueOf(0.99),
                        Map.of("sourceEvidenceId", "evidence-5090", "purpose", "REQUIREMENT_PARSE")
                )));
        when(openAiResponsesClient.createStructuredJson(anyString(), anyString(), eq("buildgraph_ai_build_chat_plan"), any()))
                .thenReturn("""
                        {
                          "intent": "FULL_BUILD_RECOMMEND",
                          "assistantMessage": "RTX 5090 조건을 유지해 추천 조합을 만들겠습니다.",
                          "selectedCategory": null,
                          "parsedContext": {
                            "budget": null,
                            "usageTags": ["GAMING"],
                            "resolution": null,
                            "preferredVendors": ["NVIDIA"],
                            "priority": null,
                            "performanceTier": "ENTHUSIAST",
                            "budgetPolicy": "OPEN_BUDGET",
                            "mustHave": [],
                            "requiredGpuClasses": ["RTX_5090"],
                            "requiredPartKeywords": [],
                            "hardConstraintPolicy": "MUST_INCLUDE",
                            "confidence": {
                              "usageTags": "HIGH",
                              "budget": "LOW",
                              "resolution": "LOW",
                              "preferredVendors": "HIGH"
                            },
                            "parseNotes": "사용자가 RTX 5090을 명시했습니다."
                          },
                          "draftEdit": {
                            "operation": "NONE",
                            "category": null,
                            "priceDirection": "ANY",
                            "targetMaxPrice": null,
                            "targetQuantity": null,
                            "reason": null
                          }
                        }
                        """);

        AiChatEngineResponse response = engine.respondLlmRequired(new AiChatEngineRequest(
                "5090 글카가 들어간 PC 추천해줘",
                "HOME",
                null,
                null,
                null,
                Map.of(),
                1L
        ));

        assertThat(response.intent()).isEqualTo(AiChatIntent.FULL_BUILD_RECOMMEND);
        assertThat(response.assistantMessage()).contains("RTX 5090");
        assertThat(response.evidenceIds()).containsExactly("evidence-5090");
        assertThat(response.parsedContext().get("requiredGpuClasses")).asList().containsExactly("RTX_5090");
        assertThat(response.recommendations()).hasSize(3);
        assertThat(response.recommendations())
                .allSatisfy(recommendation -> assertThat(recommendation.items())
                        .filteredOn(part -> "GPU".equals(part.category()))
                        .singleElement()
                        .satisfies(part -> assertThat(part.attributes()).containsEntry("gpuClass", "RTX_5090")));
        verifyNoJdbcWrites();
    }

    @Test
    void analyzeQuoteRequirementRecordsRagTraceAndReturnsStructuredContext() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);
        when(agentTraceService.createQueuedSession(any(), eq("SYSTEM"), eq(AgentPurpose.REQUIREMENT_PARSE)))
                .thenReturn("agent-session-1");
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse())))
                .thenReturn(List.of(new AgentRagEvidenceDraft(
                        "requirement-example-gaming-resolution-refresh",
                        "QHD game examples",
                        "QHD gaming evidence",
                        BigDecimal.valueOf(0.94),
                        Map.of("purpose", "REQUIREMENT_PARSE")
                )));
        when(agentTraceService.recordRagEvidence(eq("agent-session-1"), any()))
                .thenReturn("evidence-1");

        QuoteRequirementAnalysisResult result = engine.analyzeQuoteRequirement(new QuoteRequirementAnalysisRequest(
                "00000000-0000-4000-8000-000000001001",
                "200만원 QHD 게임용 PC 추천해줘",
                Map.of(),
                Map.of("usageTags", List.of("GAMING"))
        ));

        assertThat(result.agentSessionId()).isEqualTo("agent-session-1");
        assertThat(result.evidenceIds()).containsExactly("evidence-1");
        assertThat(result.parsedContext())
                .containsEntry("parseMode", "AI_CHAT_ENGINE_DETERMINISTIC")
                .containsEntry("parser", "ai-chat-engine-quote-v1");
        assertThat(result.parsedContext().get("usageTags")).asList().contains("GAMING");
        verifyNoJdbcWrites();
    }

    @Test
    void analyzeQuoteRequirementExtractsExplicitRtx5090HardConstraintWithoutLlm() {
        when(openAiResponsesClient.isConfigured()).thenReturn(false);
        when(agentTraceService.createQueuedSession(any(), eq("SYSTEM"), eq(AgentPurpose.REQUIREMENT_PARSE)))
                .thenReturn("agent-session-1");
        when(agentRagRetrievalService.retrieveEvidenceSet(any(), eq(AgentRunProfiles.requirementParse())))
                .thenReturn(List.of(new AgentRagEvidenceDraft(
                        "requirement-rule-explicit-gpu-class-hard-constraint",
                        "Explicit RTX 5090 should be a hard constraint.",
                        "Explicit GPU class hard constraint parse rule.",
                        BigDecimal.valueOf(0.99),
                        Map.of("purpose", "REQUIREMENT_PARSE")
                )));
        when(agentTraceService.recordRagEvidence(eq("agent-session-1"), any()))
                .thenReturn("evidence-1");

        QuoteRequirementAnalysisResult result = engine.analyzeQuoteRequirement(new QuoteRequirementAnalysisRequest(
                "00000000-0000-4000-8000-000000001001",
                "5090 글카가 들어간 PC 추천해줘",
                Map.of(),
                Map.of()
        ));

        assertThat(result.parsedContext().get("requiredGpuClasses")).asList().containsExactly("RTX_5090");
        assertThat(result.parsedContext()).containsEntry("hardConstraintPolicy", "MUST_INCLUDE");
        assertThat(result.parsedContext()).containsEntry("budgetPolicy", "OPEN_BUDGET");
        assertThat(result.parsedContext()).containsEntry("performanceTier", "ENTHUSIAST");
        verifyNoJdbcWrites();
    }

    private void verifyNoJdbcWrites() {
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    private static List<Map<String, Object>> partRows(String category) {
        if ("GPU".equals(category)) {
            return List.of(
                    partRow(category, "gpu-5090", "GeForce RTX 5090 32GB", 5_000_000, Map.of("toolReady", true, "gpuClass", "RTX_5090")),
                    partRow(category, "gpu-5080", "GeForce RTX 5080 16GB", 2_200_000, Map.of("toolReady", true, "gpuClass", "RTX_5080")),
                    partRow(category, "gpu-5070", "GeForce RTX 5070 12GB", 900_000, Map.of("toolReady", true, "gpuClass", "RTX_5070"))
            );
        }
        return List.of(
                partRow(category, "part-a", category + " Alpha", 900_000),
                partRow(category, "part-b", category + " Bravo", 700_000),
                partRow(category, "part-c", category + " Charlie", 500_000)
        );
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price) {
        return partRow(category, id, name, price, Map.of("toolReady", true));
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price, Map<String, Object> attributes) {
        return Map.of(
                "id", id,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", attributesJson(attributes)
        );
    }

    private static String attributesJson(Map<String, Object> attributes) {
        return "{" + attributes.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + jsonValue(entry.getValue()))
                .reduce((left, right) -> left + "," + right)
                .orElse("") + "}";
    }

    private static String jsonValue(Object value) {
        if (value instanceof Boolean bool) {
            return Boolean.toString(bool);
        }
        if (value instanceof Number number) {
            return number.toString();
        }
        return "\"" + String.valueOf(value) + "\"";
    }
}

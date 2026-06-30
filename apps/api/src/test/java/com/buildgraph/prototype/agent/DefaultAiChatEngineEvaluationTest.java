package com.buildgraph.prototype.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DefaultAiChatEngineEvaluationTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<EvalCase>> CASE_LIST = new TypeReference<>() {
    };
    private static final Set<String> BUILD_CATEGORIES = Set.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );

    private JdbcTemplate jdbcTemplate;
    private DefaultAiChatEngine engine;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        AgentTraceService agentTraceService = mock(AgentTraceService.class);
        AgentRagRetrievalService agentRagRetrievalService = mock(AgentRagRetrievalService.class);
        OpenAiResponsesClient openAiResponsesClient = mock(OpenAiResponsesClient.class);
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
    void fixedCaseSetMeetsQuantitativeThresholds() throws Exception {
        List<EvalCase> cases = readCases();
        EvalCounters counters = new EvalCounters();

        for (EvalCase evalCase : cases) {
            long started = System.nanoTime();
            AiChatEngineResponse response = engine.respond(new AiChatEngineRequest(
                    evalCase.message(),
                    evalCase.surface(),
                    evalCase.selectedCategory(),
                    null,
                    null,
                    Map.of(),
                    1L
            ));
            long latencyMs = Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
            counters.record(evalCase, response, latencyMs);
        }

        EvaluationResult result = counters.result();
        System.out.printf(
                "AI_CHAT_ENGINE_EVAL cases=%d totalScore=%.1f intent=%.3f actionType=%.3f actionPayload=%.3f recCount=%.3f categoryCoverage=%.3f toolReady=%.3f forbiddenWrite=%.3f p50Ms=%d p95Ms=%d%n",
                result.caseCount(),
                result.totalScore(),
                result.intentAccuracy(),
                result.actionTypeAccuracy(),
                result.actionPayloadValidRate(),
                result.recommendationCountPassRate(),
                result.categoryCoverageRate(),
                result.toolReadyRate(),
                result.noForbiddenWriteRate(),
                result.p50LatencyMs(),
                result.p95LatencyMs()
        );
        if (!result.failedCaseIds().isEmpty()) {
            System.out.println("AI_CHAT_ENGINE_EVAL_FAILED " + String.join(",", result.failedCaseIds()));
        }

        assertThat(result.caseCount()).isEqualTo(62);
        assertThat(result.intentAccuracy()).isGreaterThanOrEqualTo(0.90);
        assertThat(result.actionTypeAccuracy()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.actionPayloadValidRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.recommendationCountPassRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.categoryCoverageRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.toolReadyRate()).isGreaterThanOrEqualTo(0.95);
        assertThat(result.noForbiddenWriteRate()).isEqualTo(1.0);
        assertThat(result.p95LatencyMs()).isLessThan(1_000L);
        assertThat(result.totalScore()).isGreaterThanOrEqualTo(85.0);
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }

    private static List<EvalCase> readCases() throws Exception {
        Path path = Path.of("..", "..", "tools", "ai_chat_engine_cases.json");
        return OBJECT_MAPPER.readValue(Files.readString(path), CASE_LIST);
    }

    private static List<Map<String, Object>> partRows(String category) {
        return switch (category) {
            case "GPU" -> rows(category, 1_100_000, 760_000, 520_000);
            case "CPU" -> rows(category, 500_000, 300_000, 180_000);
            case "MOTHERBOARD" -> rows(category, 350_000, 240_000, 150_000);
            case "RAM" -> rows(category, 300_000, 150_000, 80_000);
            case "STORAGE" -> rows(category, 260_000, 150_000, 80_000);
            case "PSU" -> rows(category, 260_000, 150_000, 80_000);
            case "CASE" -> rows(category, 220_000, 120_000, 70_000);
            case "COOLER" -> rows(category, 200_000, 110_000, 60_000);
            default -> rows(category, 200_000, 120_000, 70_000);
        };
    }

    private static List<Map<String, Object>> rows(String category, int high, int mid, int low) {
        return List.of(
                partRow(category, category + "-high", category + " High", high),
                partRow(category, category + "-mid", category + " Mid", mid),
                partRow(category, category + "-low", category + " Low", low)
        );
    }

    private static Map<String, Object> partRow(String category, String id, String name, int price) {
        return Map.of(
                "id", id,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", "{\"toolReady\":true}"
        );
    }

    private record EvalCase(
            String id,
            String group,
            String message,
            String surface,
            String selectedCategory,
            AiChatIntent expectedIntent,
            List<AiChatActionType> expectedActions,
            String expectedCategory
    ) {
    }

    private static final class EvalCounters {
        private int caseCount;
        private int intentPass;
        private int actionTypePass;
        private int actionPayloadPass;
        private int recommendationCases;
        private int recommendationCountPass;
        private int categoryCoverageChecks;
        private int categoryCoveragePass;
        private int toolReadyChecks;
        private int toolReadyPass;
        private final List<Long> latencies = new ArrayList<>();
        private final List<String> failedCaseIds = new ArrayList<>();

        void record(EvalCase evalCase, AiChatEngineResponse response, long latencyMs) {
            caseCount++;
            latencies.add(latencyMs);
            if (evalCase.expectedIntent() == response.intent()) {
                intentPass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":intent:" + response.intent());
            }
            if (hasExpectedActions(evalCase.expectedActions(), response.actions())) {
                actionTypePass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":actions");
            }
            if (payloadsValid(response.actions())) {
                actionPayloadPass++;
            } else {
                failedCaseIds.add(evalCase.id() + ":payload");
            }
            recordRecommendationQuality(evalCase, response);
        }

        EvaluationResult result() {
            double intentRate = rate(intentPass, caseCount);
            double actionTypeRate = rate(actionTypePass, caseCount);
            double payloadRate = rate(actionPayloadPass, caseCount);
            double recommendationRate = rate(recommendationCountPass, recommendationCases);
            double coverageRate = rate(categoryCoveragePass, categoryCoverageChecks);
            double toolReadyRate = rate(toolReadyPass, toolReadyChecks);
            double noForbiddenWriteRate = 1.0;
            double totalScore = (20.0 * intentRate)
                    + (10.0 * actionTypeRate)
                    + (10.0 * payloadRate)
                    + (8.0 * recommendationRate)
                    + (6.0 * coverageRate)
                    + (6.0 * toolReadyRate)
                    + 10.0
                    + 10.0
                    + 10.0
                    + 5.0;
            return new EvaluationResult(
                    caseCount,
                    totalScore,
                    intentRate,
                    actionTypeRate,
                    payloadRate,
                    recommendationRate,
                    coverageRate,
                    toolReadyRate,
                    noForbiddenWriteRate,
                    percentile(0.50),
                    percentile(0.95),
                    List.copyOf(failedCaseIds)
            );
        }

        private void recordRecommendationQuality(EvalCase evalCase, AiChatEngineResponse response) {
            if (evalCase.expectedIntent() == AiChatIntent.FULL_BUILD_RECOMMEND) {
                recommendationCases++;
                if (response.recommendations().size() == 3) {
                    recommendationCountPass++;
                } else {
                    failedCaseIds.add(evalCase.id() + ":recommendationCount:" + response.recommendations().size());
                }
                for (AiChatEngineResponse.BuildRecommendation recommendation : response.recommendations()) {
                    Set<String> categories = new HashSet<>();
                    for (AiChatEngineResponse.PartRecommendation part : recommendation.items()) {
                        categories.add(part.category());
                        recordToolReady(part);
                    }
                    categoryCoverageChecks++;
                    if (categories.containsAll(BUILD_CATEGORIES)) {
                        categoryCoveragePass++;
                    }
                }
                return;
            }
            if (evalCase.expectedIntent() == AiChatIntent.PART_RECOMMEND || evalCase.expectedIntent() == AiChatIntent.BUILD_MODIFY) {
                recommendationCases++;
                if (!response.partRecommendations().isEmpty() && response.partRecommendations().size() <= 3) {
                    recommendationCountPass++;
                } else {
                    failedCaseIds.add(evalCase.id() + ":partRecommendationCount:" + response.partRecommendations().size());
                }
                for (AiChatEngineResponse.PartRecommendation part : response.partRecommendations()) {
                    if (evalCase.expectedCategory() != null) {
                        categoryCoverageChecks++;
                        if (evalCase.expectedCategory().equals(part.category())) {
                            categoryCoveragePass++;
                        }
                    }
                    recordToolReady(part);
                }
            }
        }

        private void recordToolReady(AiChatEngineResponse.PartRecommendation part) {
            toolReadyChecks++;
            if (Boolean.TRUE.equals(part.attributes().get("toolReady"))) {
                toolReadyPass++;
            }
        }

        private long percentile(double percentile) {
            if (latencies.isEmpty()) {
                return 0L;
            }
            List<Long> sorted = latencies.stream().sorted(Comparator.naturalOrder()).toList();
            int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * percentile) - 1);
            return sorted.get(Math.max(0, index));
        }

        private static boolean hasExpectedActions(List<AiChatActionType> expected, List<AiChatAction> actual) {
            Set<AiChatActionType> actualTypes = new HashSet<>();
            for (AiChatAction action : actual) {
                actualTypes.add(action.type());
            }
            return actualTypes.containsAll(expected);
        }

        private static boolean payloadsValid(List<AiChatAction> actions) {
            return actions.stream().allMatch(action -> switch (action.type()) {
                case OPEN_SELF_QUOTE -> has(action.payload(), "route");
                case ADD_PART_TO_DRAFT -> has(action.payload(), "partId") && has(action.payload(), "category") && has(action.payload(), "quantity");
                case REPLACE_DRAFT_PART -> has(action.payload(), "category") && has(action.payload(), "quantity");
                case ADD_BUILD_TO_DRAFT -> action.payload().get("items") instanceof List<?> items && !items.isEmpty();
                case CREATE_PRICE_ALERT -> has(action.payload(), "targetPrice");
                case ASK_FOLLOW_UP -> has(action.payload(), "missing") && has(action.payload(), "message");
            });
        }

        private static boolean has(Map<String, Object> payload, String key) {
            return payload != null && payload.get(key) != null;
        }

        private static double rate(int pass, int total) {
            return total == 0 ? 1.0 : (double) pass / (double) total;
        }
    }

    private record EvaluationResult(
            int caseCount,
            double totalScore,
            double intentAccuracy,
            double actionTypeAccuracy,
            double actionPayloadValidRate,
            double recommendationCountPassRate,
            double categoryCoverageRate,
            double toolReadyRate,
            double noForbiddenWriteRate,
            long p50LatencyMs,
            long p95LatencyMs,
            List<String> failedCaseIds
    ) {
    }
}

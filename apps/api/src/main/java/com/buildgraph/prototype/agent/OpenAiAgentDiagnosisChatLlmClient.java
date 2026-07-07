package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class OpenAiAgentDiagnosisChatLlmClient implements AgentDiagnosisChatLlmClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final int LLM_RECENT_MESSAGE_LIMIT = 4;
    private static final String SCHEMA_NAME = "buildgraph_pc_agent_diagnosis_chat_response";
    private static final String SYSTEM_PROMPT = """
            당신은 BuildGraph PCAgent의 로컬 PC 진단 챗봇입니다.
            응답은 서버가 제공한 Structured Output JSON schema를 반드시 따릅니다.
            사용자가 상태 탭에서 실행한 최신 PC 진단 결과와 최근 대화만 근거로 답하십시오.
            확인되지 않은 부품명, 수리 비용, FPS, 원격 조치 결과를 지어내지 마십시오.
            AS 티켓, 로그 업로드, AS 채팅 세션을 생성했다고 말하지 마십시오.
            AS 접수는 사용자가 상태 탭에서 별도로 동의하고 AS 접수 신청을 눌렀을 때만 진행된다고 안내하십시오.
            위험 신호가 있으면 먼저 백업, 전원 종료, 발열/전원/저장장치 점검처럼 안전한 조치를 권하십시오.
            사용자가 바로 따라 할 수 있는 짧은 단계 중심으로 한국어로 답하십시오.
            """;

    private final OpenAiResponsesClient openAiResponsesClient;
    private final String model;
    private final String reasoningEffort;
    private final Integer maxOutputTokens;

    public OpenAiAgentDiagnosisChatLlmClient(
            OpenAiResponsesClient openAiResponsesClient,
            @Value("${agent.diagnosis-chat.model:}") String model,
            @Value("${agent.diagnosis-chat.reasoning-effort:low}") String reasoningEffort,
            @Value("${agent.diagnosis-chat.max-output-tokens:750}") Integer maxOutputTokens
    ) {
        this.openAiResponsesClient = openAiResponsesClient;
        this.model = blankToNull(model);
        this.reasoningEffort = blankToNull(reasoningEffort) == null ? "low" : reasoningEffort.trim();
        this.maxOutputTokens = maxOutputTokens == null || maxOutputTokens <= 0 ? 750 : maxOutputTokens;
    }

    @Override
    public Optional<Result> reply(Prompt prompt) {
        if (!openAiResponsesClient.isConfigured()) {
            return Optional.empty();
        }
        LlmResponseResult result = openAiResponsesClient.createStructuredJsonResult(
                SYSTEM_PROMPT,
                promptJson(prompt),
                SCHEMA_NAME,
                responseSchema(prompt.evidenceIds()),
                model == null ? openAiResponsesClient.model() : model,
                reasoningEffort,
                maxOutputTokens
        );
        return Optional.of(new Result(strictJson(result.text()), result.model(), result.latencyMs()));
    }

    private static String promptJson(Prompt prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userMessage", prompt.userMessage());
        payload.put("diagnosisContext", prompt.diagnosisContext());
        payload.put("rawDiagnosis", prompt.rawDiagnosis());
        payload.put("recentMessages", compactRecentMessages(prompt.recentMessages()));
        payload.put("ruleHints", ruleHints(prompt.ruleFallback()));
        payload.put("responseGuidance", MockData.map(
                "assistantMessage", "Korean, conversational, under 420 characters unless the user asks for detail",
                "causeCandidatesMax", 2,
                "nextActionsMax", 3,
                "doNotCreateTicket", true,
                "asEntryPoint", "상태 탭의 AS 접수 신청 버튼"
        ));
        try {
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("PC Agent diagnosis chat prompt cannot be serialized.", error);
        }
    }

    private static List<Map<String, Object>> compactRecentMessages(List<Map<String, Object>> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        int start = Math.max(0, messages.size() - LLM_RECENT_MESSAGE_LIMIT);
        return messages.subList(start, messages.size()).stream()
                .map(item -> MockData.map(
                        "role", String.valueOf(item.getOrDefault("role", "")),
                        "content", trimText(String.valueOf(item.getOrDefault("content", "")), 600)
                ))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ruleHints(Map<String, Object> ruleFallback) {
        Map<String, Object> hints = new LinkedHashMap<>();
        Object escalation = ruleFallback.get("escalation");
        if (escalation instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, value) -> copied.put(String.valueOf(key), value));
            hints.put("escalation", copied);
        }
        Object causes = ruleFallback.get("causeCandidates");
        if (causes instanceof List<?> list) {
            hints.put("causeCandidates", list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .limit(2)
                    .map(item -> MockData.map(
                            "label", item.get("label"),
                            "confidence", item.get("confidence"),
                            "reason", trimText(String.valueOf(item.getOrDefault("reason", "")), 240)
                    ))
                    .toList());
        }
        Object actions = ruleFallback.get("nextActions");
        if (actions instanceof List<?> list) {
            hints.put("nextActions", list.stream()
                    .filter(Map.class::isInstance)
                    .map(Map.class::cast)
                    .limit(3)
                    .map(item -> MockData.map(
                            "label", item.get("label"),
                            "priority", item.get("priority"),
                            "instruction", trimText(String.valueOf(item.getOrDefault("instruction", "")), 240)
                    ))
                    .toList());
        }
        return hints;
    }

    private static Map<String, Object> strictJson(String raw) {
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(normalizeJsonPayload(raw), MAP_TYPE);
            requireJsonField(parsed, "assistantMessage");
            requireJsonField(parsed, "causeCandidates");
            requireJsonField(parsed, "nextActions");
            requireJsonField(parsed, "escalation");
            requireJsonField(parsed, "ticketDraft");
            return parsed;
        } catch (Exception error) {
            throw new IllegalArgumentException("PC Agent diagnosis chat LLM JSON is invalid.", error);
        }
    }

    private static void requireJsonField(Map<String, Object> parsed, String key) {
        if (!parsed.containsKey(key) || parsed.get(key) == null) {
            throw new IllegalArgumentException("LLM response missing required field: " + key);
        }
    }

    private static String normalizeJsonPayload(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        if (text.startsWith("{") && text.endsWith("}")) {
            return text;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1).trim();
        }
        return text;
    }

    private static Map<String, Object> responseSchema(List<String> evidenceIds) {
        return schemaObject(
                List.of("assistantMessage", "causeCandidates", "nextActions", "escalation", "ticketDraft"),
                MockData.map(
                        "assistantMessage", stringSchema(),
                        "causeCandidates", arraySchema(schemaObject(
                                List.of("label", "confidence", "reason", "evidenceIds", "toolInvocationIds"),
                                MockData.map(
                                        "label", stringSchema(),
                                        "confidence", enumSchema("LOW", "MEDIUM", "HIGH"),
                                        "reason", stringSchema(),
                                        "evidenceIds", idArraySchema(evidenceIds),
                                        "toolInvocationIds", stringArraySchema()
                                )
                        )),
                        "nextActions", arraySchema(schemaObject(
                                List.of("label", "priority", "instruction", "evidenceIds", "toolInvocationIds"),
                                MockData.map(
                                        "label", stringSchema(),
                                        "priority", enumSchema("LOW", "MEDIUM", "HIGH"),
                                        "instruction", stringSchema(),
                                        "evidenceIds", idArraySchema(evidenceIds),
                                        "toolInvocationIds", stringArraySchema()
                                )
                        )),
                        "escalation", schemaObject(
                                List.of("required", "recommended", "reason"),
                                MockData.map(
                                        "required", MockData.map("type", "boolean"),
                                        "recommended", MockData.map("type", "boolean"),
                                        "reason", stringSchema()
                                )
                        ),
                        "ticketDraft", schemaObject(
                                List.of("symptomSummary", "recommendedLogRequest"),
                                MockData.map(
                                        "symptomSummary", stringSchema(),
                                        "recommendedLogRequest", stringSchema()
                                )
                        )
                )
        );
    }

    private static Map<String, Object> schemaObject(List<String> required, Map<String, Object> properties) {
        return MockData.map(
                "type", "object",
                "additionalProperties", false,
                "required", required,
                "properties", properties
        );
    }

    private static Map<String, Object> stringSchema() {
        return MockData.map("type", "string");
    }

    private static Map<String, Object> enumSchema(String... values) {
        return MockData.map(
                "type", "string",
                "enum", List.of(values)
        );
    }

    private static Map<String, Object> arraySchema(Map<String, Object> items) {
        return MockData.map(
                "type", "array",
                "items", items
        );
    }

    private static Map<String, Object> idArraySchema(List<String> allowedIds) {
        Map<String, Object> itemSchema = allowedIds == null || allowedIds.isEmpty()
                ? stringSchema()
                : MockData.map("type", "string", "enum", allowedIds);
        return arraySchema(itemSchema);
    }

    private static Map<String, Object> stringArraySchema() {
        return arraySchema(stringSchema());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trimText(String value, int maxLength) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").trim();
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, maxLength) + "...";
    }
}

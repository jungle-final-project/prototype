package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.config.security.AgentPrincipal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class AgentDiagnosisChatService {
    private static final Logger log = LoggerFactory.getLogger(AgentDiagnosisChatService.class);
    private static final int RECENT_MESSAGE_LIMIT = 8;

    private final AgentDiagnosisChatLlmClient llmClient;

    public AgentDiagnosisChatService(AgentDiagnosisChatLlmClient llmClient) {
        this.llmClient = llmClient;
    }

    public Map<String, Object> reply(AgentPrincipal principal, Map<String, Object> request) {
        if (principal == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Agent token is required.");
        }
        String message = requiredString(request, "message");
        Map<String, Object> diagnosis = mapValue(request.get("diagnosis"));
        DiagnosisContext context = DiagnosisContext.from(diagnosis);
        List<Map<String, Object>> recentMessages = recentMessages(request.get("messages"));
        List<Map<String, Object>> causeCandidates = causeCandidates(context);
        List<Map<String, Object>> nextActions = nextActions(context, message);
        Map<String, Object> escalation = escalation(context, message);
        Map<String, Object> ruleResponse = ruleResponse(context, message, recentMessages, causeCandidates, nextActions, escalation);
        AgentDiagnosisChatLlmClient.Prompt llmPrompt = new AgentDiagnosisChatLlmClient.Prompt(
                message,
                diagnosis,
                diagnosisContext(context),
                recentMessages,
                ruleResponse,
                context.evidenceIds()
        );

        long startedAt = System.nanoTime();
        try {
            return llmClient.reply(llmPrompt)
                    .map(result -> {
                        log.info(
                                "PC Agent diagnosis chat LLM response generated: model={} latencyMs={} recentMessages={} evidenceIds={}",
                                result.model(),
                                result.latencyMs(),
                                recentMessages.size(),
                                context.evidenceIds().size()
                        );
                        return llmResponse(result, ruleResponse);
                    })
                    .orElseGet(() -> {
                        log.info(
                                "PC Agent diagnosis chat rule fallback used: reason=LLM_NOT_CONFIGURED latencyMs={} recentMessages={} evidenceIds={}",
                                elapsedMs(startedAt),
                                recentMessages.size(),
                                context.evidenceIds().size()
                        );
                        return ruleResponse;
                    });
        } catch (RuntimeException error) {
            log.warn(
                    "PC Agent diagnosis chat rule fallback used: reason={} latencyMs={} recentMessages={} evidenceIds={}",
                    safeReason(error),
                    elapsedMs(startedAt),
                    recentMessages.size(),
                    context.evidenceIds().size()
            );
            return ruleResponse;
        }
    }

    private static Map<String, Object> ruleResponse(
            DiagnosisContext context,
            String message,
            List<Map<String, Object>> recentMessages,
            List<Map<String, Object>> causeCandidates,
            List<Map<String, Object>> nextActions,
            Map<String, Object> escalation
    ) {
        return MockData.map(
                "assistantMessage", assistantMessage(context, message, nextActions, escalation, recentMessages.size()),
                "causeCandidates", causeCandidates,
                "nextActions", nextActions,
                "escalation", escalation,
                "ticketDraft", ticketDraft(context),
                "model", "buildgraph-agent-diagnosis-rule-v1",
                "evidence", evidence(context),
                "toolResults", List.of(),
                "messageLimit", RECENT_MESSAGE_LIMIT
        );
    }

    private static Map<String, Object> llmResponse(
            AgentDiagnosisChatLlmClient.Result result,
            Map<String, Object> ruleResponse
    ) {
        Map<String, Object> payload = result.payload();
        return MockData.map(
                "assistantMessage", textOrFallback(payload, "assistantMessage", ruleResponse.get("assistantMessage")),
                "causeCandidates", listOrFallback(payload.get("causeCandidates"), ruleResponse.get("causeCandidates")),
                "nextActions", listOrFallback(payload.get("nextActions"), ruleResponse.get("nextActions")),
                "escalation", objectOrFallback(payload.get("escalation"), ruleResponse.get("escalation")),
                "ticketDraft", objectOrFallback(payload.get("ticketDraft"), ruleResponse.get("ticketDraft")),
                "model", firstNonBlank(result.model(), "buildgraph-agent-diagnosis-llm-v1"),
                "evidence", ruleResponse.get("evidence"),
                "toolResults", ruleResponse.get("toolResults"),
                "messageLimit", RECENT_MESSAGE_LIMIT
        );
    }

    private static Map<String, Object> diagnosisContext(DiagnosisContext context) {
        return MockData.map(
                "summary", context.summary(),
                "recommendedService", context.recommendedService(),
                "recommendedDecision", context.recommendedDecision(),
                "confidence", context.confidence(),
                "primaryCause", context.primaryCause(),
                "secondaryCause", context.secondaryCause(),
                "reason", context.reason(),
                "requiresVisit", context.requiresVisit(),
                "remoteRecommended", context.remoteRecommended(),
                "evidenceIds", context.evidenceIds()
        );
    }

    private static List<?> listOrFallback(Object value, Object fallback) {
        if (value instanceof List<?> list && !list.isEmpty()) {
            return list.stream().limit(3).toList();
        }
        return fallback instanceof List<?> list ? list : List.of();
    }

    private static Map<String, Object> objectOrFallback(Object value, Object fallback) {
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (fallback instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static String textOrFallback(Map<String, Object> payload, String key, Object fallback) {
        Object value = payload.get(key);
        if (value instanceof String text && !text.isBlank()) {
            return text.trim();
        }
        return fallback == null ? "" : String.valueOf(fallback);
    }

    private static String assistantMessage(
            DiagnosisContext context,
            String message,
            List<Map<String, Object>> nextActions,
            Map<String, Object> escalation,
            int recentMessageCount
    ) {
        String intent = intent(message);
        StringBuilder builder = new StringBuilder();
        if ("ESCALATION".equals(intent)) {
            builder.append(Boolean.TRUE.equals(escalation.get("required"))
                    ? "현재 진단만 보면 AS 접수를 권장할 신호가 있습니다. "
                    : "현재 진단만으로는 바로 AS 접수부터 할 단계는 아닙니다. ");
        } else if ("RISK".equals(intent)) {
            builder.append(riskOpening(context));
        } else if ("CAUSE".equals(intent)) {
            builder.append("가장 가능성이 높은 원인은 ").append(context.primaryCause()).append("입니다. ");
        } else {
            builder.append(context.summary()).append(" ");
        }

        if (!nextActions.isEmpty()) {
            builder.append("먼저 ").append(nextActions.get(0).get("label")).append("부터 확인해 보세요. ");
        }
        if (recentMessageCount > 0) {
            builder.append("이전 대화와 최근 진단 결과를 같이 참고했습니다. ");
        }
        builder.append("이 상담은 티켓을 만들지 않으며, AS 접수는 접수 버튼을 직접 누를 때만 진행됩니다.");
        return builder.toString();
    }

    private static String riskOpening(DiagnosisContext context) {
        if (context.requiresVisit()) {
            return "디스크, 전원, WHEA/BSOD, 과열처럼 현장 점검이 필요한 신호가 있어 주의가 필요합니다. ";
        }
        if (context.remoteRecommended()) {
            return "치명적인 하드웨어 고장 신호보다는 드라이버, 앱, 네트워크처럼 원격 조치가 가능한 신호에 가깝습니다. ";
        }
        return "현재 진단은 즉시 위험한 상태로 보이지 않습니다. ";
    }

    private static List<Map<String, Object>> causeCandidates(DiagnosisContext context) {
        List<Map<String, Object>> causes = new ArrayList<>();
        causes.add(MockData.map(
                "label", context.primaryCause(),
                "confidence", context.confidence(),
                "reason", context.reason(),
                "evidenceIds", context.evidenceIds(),
                "toolInvocationIds", List.of()
        ));
        if (!context.secondaryCause().isBlank()) {
            causes.add(MockData.map(
                    "label", context.secondaryCause(),
                    "confidence", "LOW",
                    "reason", "진단 요약에서 함께 확인할 수 있는 보조 가능성입니다.",
                    "evidenceIds", List.of(),
                    "toolInvocationIds", List.of()
            ));
        }
        return causes;
    }

    private static List<Map<String, Object>> nextActions(DiagnosisContext context, String message) {
        List<Map<String, Object>> actions = new ArrayList<>();
        String lower = message.toLowerCase(Locale.ROOT);
        if (context.requiresVisit()) {
            actions.add(action("PC 전원을 끄고 저장장치/전원/과열 상태 확인", "HIGH",
                    "반복 재부팅, WHEA/BSOD, SMART, Kernel-Power, 심한 과열 신호가 있으면 추가 사용을 줄이고 현장 점검을 준비하세요."));
            actions.add(action("중요 파일 백업", "HIGH",
                    "디스크 오류나 전원 불안정 신호가 있으면 진단보다 백업을 먼저 진행하세요."));
        } else if (context.remoteRecommended()) {
            actions.add(action("드라이버와 문제 앱 재시작/업데이트", "HIGH",
                    "그래픽 드라이버, 게임 런처, 네트워크 어댑터처럼 로그에 반복된 항목을 먼저 재시작하거나 업데이트하세요."));
            actions.add(action("최근 설치/업데이트 되돌아보기", "MEDIUM",
                    "문제가 시작된 시점 전후의 앱, 드라이버, Windows 업데이트를 확인하세요."));
        } else {
            actions.add(action("문제 재현 조건 기록", "MEDIUM",
                    "언제, 어떤 앱에서, 몇 분 뒤 발생하는지 적어두면 다음 진단 정확도가 올라갑니다."));
            actions.add(action("상태 탭에서 다시 진단", "LOW",
                    "증상이 다시 나타난 직후 상태 탭의 PC 진단받기를 눌러 최근 30분 로그를 새로 분석하세요."));
        }
        if (lower.contains("as") || lower.contains("접수")) {
            actions.add(action("AS 접수 전 증상 문장 정리", "MEDIUM",
                    "접수하려면 발생 시각, 실행 중이던 앱, 화면/소리/재부팅 여부를 한 문장으로 정리하세요."));
        }
        return actions.stream().limit(3).toList();
    }

    private static Map<String, Object> escalation(DiagnosisContext context, String message) {
        boolean required = context.requiresVisit();
        boolean recommended = required || context.remoteRecommended();
        String reason;
        if (required) {
            reason = "현장 점검이 필요한 하드웨어/전원/과열 계열 신호가 포함되어 있습니다.";
        } else if (context.remoteRecommended()) {
            reason = "원격으로 확인 가능한 드라이버/앱/네트워크 계열 신호가 반복됩니다.";
        } else if (message.toLowerCase(Locale.ROOT).contains("as") || message.contains("접수")) {
            reason = "사용자가 AS 접수를 문의했지만 현재 진단은 자가 확인을 먼저 권장합니다.";
        } else {
            reason = "현재 진단만으로는 AS 접수를 바로 요구하지 않습니다.";
        }
        return MockData.map(
                "required", required,
                "recommended", recommended,
                "reason", reason
        );
    }

    private static Map<String, Object> ticketDraft(DiagnosisContext context) {
        return MockData.map(
                "symptomSummary", context.summary(),
                "recommendedLogRequest", "상태 탭에서 증상 직후 최근 30분 로그를 다시 진단한 뒤 AS 접수를 진행하세요."
        );
    }

    private static List<Map<String, Object>> evidence(DiagnosisContext context) {
        if (context.evidenceIds().isEmpty()) {
            return List.of();
        }
        return context.evidenceIds().stream()
                .map(id -> MockData.map("id", id, "summary", "PC Agent 최근 진단 결과"))
                .toList();
    }

    private static Map<String, Object> action(String label, String priority, String instruction) {
        return MockData.map(
                "label", label,
                "priority", priority,
                "instruction", instruction,
                "evidenceIds", List.of(),
                "toolInvocationIds", List.of()
        );
    }

    private static String intent(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("as") || message.contains("접수") || message.contains("기사")) {
            return "ESCALATION";
        }
        if (message.contains("위험") || message.contains("괜찮") || message.contains("심각")) {
            return "RISK";
        }
        if (message.contains("원인") || message.contains("왜")) {
            return "CAUSE";
        }
        return "ACTION";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recentMessages(Object value) {
        if (!(value instanceof List<?> items)) {
            return List.of();
        }
        int start = Math.max(0, items.size() - RECENT_MESSAGE_LIMIT);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : items.subList(start, items.size())) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> message = new LinkedHashMap<>();
                map.forEach((key, entry) -> message.put(String.valueOf(key), entry));
                result.add(message);
            }
        }
        return result;
    }

    private static String requiredString(Map<String, Object> request, String field) {
        Object value = request == null ? null : request.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_ERROR",
                    field + " is required.",
                    Map.of("field", field)
            );
        }
        return text.trim();
    }

    private static long elapsedMs(long startedAt) {
        return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private record DiagnosisContext(
            String summary,
            String recommendedService,
            String recommendedDecision,
            String confidence,
            String primaryCause,
            String secondaryCause,
            String reason,
            List<String> evidenceIds
    ) {
        static DiagnosisContext from(Map<String, Object> diagnosis) {
            String summary = firstText(
                    diagnosis,
                    "summary",
                    "summaryText",
                    "diagnosisSummary",
                    "assistantMessage",
                    "homeSummary"
            );
            if (summary.isBlank()) {
                summary = "최근 30분 진단 결과가 아직 충분하지 않습니다.";
            }
            String recommendedService = text(diagnosis, "recommendedService", "SELF_CHECK");
            String recommendedDecision = firstNonBlank(
                    text(diagnosis, "recommendedDecision", ""),
                    text(diagnosis, "supportDecision", ""),
                    text(diagnosis, "decision", "SELF_CHECK")
            );
            String confidence = normalizeConfidence(text(diagnosis, "confidence", "MEDIUM"));
            String primaryCause = AgentDiagnosisChatService.primaryCause(diagnosis, recommendedService, summary);
            String secondaryCause = AgentDiagnosisChatService.secondaryCause(diagnosis);
            String reason = firstNonBlank(
                    text(diagnosis, "reason", ""),
                    text(diagnosis, "diagnosisReason", ""),
                    "최근 PC Agent 진단 결과와 사용자의 질문을 함께 참고했습니다."
            );
            return new DiagnosisContext(
                    summary,
                    recommendedService,
                    recommendedDecision,
                    confidence,
                    primaryCause,
                    secondaryCause,
                    reason,
                    AgentDiagnosisChatService.evidenceIds(diagnosis)
            );
        }

        boolean requiresVisit() {
            String combined = (recommendedService + " " + recommendedDecision).toUpperCase(Locale.ROOT);
            return combined.contains("VISIT") || combined.contains("REQUIRED");
        }

        boolean remoteRecommended() {
            String combined = (recommendedService + " " + recommendedDecision).toUpperCase(Locale.ROOT);
            return combined.contains("REMOTE");
        }
    }

    private static String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = text(map, key, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String text(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String normalizeConfidence(String confidence) {
        String value = confidence.toUpperCase(Locale.ROOT);
        return switch (value) {
            case "LOW", "MEDIUM", "HIGH" -> value;
            default -> "MEDIUM";
        };
    }

    private static String primaryCause(Map<String, Object> diagnosis, String recommendedService, String summary) {
        Object candidates = diagnosis.get("causeCandidates");
        if (candidates instanceof List<?> list && !list.isEmpty()) {
            Object first = list.get(0);
            if (first instanceof Map<?, ?> map) {
                Object label = map.get("label");
                if (label != null && !String.valueOf(label).isBlank()) {
                    return String.valueOf(label).trim();
                }
            }
        }
        String service = recommendedService.toUpperCase(Locale.ROOT);
        if (service.contains("VISIT")) {
            return "하드웨어 또는 전원/과열 계열 문제 가능성";
        }
        if (service.contains("REMOTE")) {
            return "드라이버, 앱, 네트워크 계열 문제 가능성";
        }
        if (summary.contains("온도") || summary.toLowerCase(Locale.ROOT).contains("thermal")) {
            return "온도 상승 또는 냉각 문제 가능성";
        }
        return "일시적 오류 또는 재현 조건 미확인";
    }

    private static String secondaryCause(Map<String, Object> diagnosis) {
        Object riskFlags = diagnosis.get("riskFlags");
        if (riskFlags instanceof List<?> list && !list.isEmpty()) {
            return String.valueOf(list.get(0)).trim();
        }
        return "";
    }

    private static List<String> evidenceIds(Map<String, Object> diagnosis) {
        Object evidence = diagnosis.get("evidenceIds");
        if (!(evidence instanceof List<?> list)) {
            return List.of();
        }
        return list.stream()
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .limit(5)
                .toList();
    }
}

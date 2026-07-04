package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.PartRouteResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Build Chat 축소 정책(2026-07 회의)의 intent 라우터.
 * 지원 범위는 예산/그래프 기반 견적 추천, 부품 교체 성능 시뮬레이션, 명확화 질문뿐이다.
 * 화면 이동, 장바구니 조작, 단일 부품 추천, 일반 상담은 UNSUPPORTED로 고정 안내한다.
 */
@Service
public class BuildChatIntentRouter {
    public BuildChatIntentDecision decide(Map<String, Object> request, String message) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String normalized = normalize(message);
        String category = firstText(text(body.get("selectedCategory")), BuildChatService.detectPartCategory(message), PartRouteResolver.inferCategory(message));
        String partQuery = PartRouteResolver.extractPartQuery(message);
        boolean hasDraftItems = !objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty();

        if (isSimulationIntent(normalized)) {
            List<String> reasons = new ArrayList<>();
            if (partQuery == null && category != null) {
                reasons.add("SIMULATION_TARGET_MAY_BE_AMBIGUOUS");
            }
            return decision(BuildChatIntent.SIMULATE_REPLACEMENT, "HIGH", "NONE", category, partQuery, "FAST_SIMULATION", "NONE",
                    semanticSignature(BuildChatIntent.SIMULATE_REPLACEMENT, category, partQuery, null), reasons);
        }

        if (isBuildRecommend(normalized, message) || isDraftCompletionIntent(normalized, hasDraftItems)) {
            return decision(BuildChatIntent.BUILD_RECOMMEND, "HIGH", "NONE", category, partQuery, "LLM_OR_DETERMINISTIC",
                    standaloneContext(body) ? "SEMANTIC_READ_ONLY" : "EXACT_ONLY",
                    semanticSignature(BuildChatIntent.BUILD_RECOMMEND, category, partQuery, budgetSignature(message)), List.of());
        }

        if (normalized.isBlank()
                || containsAny(normalized, "컴퓨터하나", "아무거나", "뭐사지")
                || isMissingMonitorContext(normalized)) {
            return decision(BuildChatIntent.ASK_CLARIFICATION, "LOW", "NONE", category, partQuery, "FAST_CLARIFICATION", "NONE", null,
                    List.of("LOW_INFORMATION"));
        }

        return decision(BuildChatIntent.UNSUPPORTED, "HIGH", "NONE", category, partQuery, "FAST_UNSUPPORTED", "NONE", null,
                List.of());
    }

    private static BuildChatIntentDecision decision(
            BuildChatIntent intent,
            String confidence,
            String sideEffectRisk,
            String category,
            String partQuery,
            String preferredPath,
            String cachePolicy,
            String semanticConstraintSignature,
            List<String> ambiguityReasons
    ) {
        return new BuildChatIntentDecision(intent, confidence, sideEffectRisk, category, partQuery, preferredPath, cachePolicy,
                semanticConstraintSignature, ambiguityReasons == null ? List.of() : ambiguityReasons);
    }

    private static boolean isSimulationIntent(String normalized) {
        boolean whatIf = containsAny(normalized, "바꾸면", "교체하면", "넣으면", "달면", "변경하면", "업그레이드하면", "으로가면", "로가면");
        boolean impact = containsAny(normalized, "프레임", "fps", "성능", "벤치", "얼마나", "어떻게되", "어떻게", "어떨", "차이", "향상");
        boolean shortWhatIfQuestion = whatIf && (normalized.endsWith("?") || normalized.endsWith("면") || containsAny(normalized, "좋을까", "나을까"));
        return whatIf && (impact || shortWhatIfQuestion);
    }

    private static boolean isRecommendationIntent(String normalized) {
        return containsAny(normalized, "추천", "맞춰", "짜줘", "구성", "골라");
    }

    private static boolean isBuildRecommend(String normalized, String message) {
        boolean explicitRecommend = isRecommendationIntent(normalized)
                && (containsAny(normalized,
                "pc", "컴퓨터", "본체", "견적", "조립컴", "조립pc", "구성",
                "목표", "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크")
                || BuildChatService.parseBudgetWon(message) != null);
        boolean budgetWithUseCase = BuildChatService.parseBudgetWon(message) != null
                && hasBuildUseCaseSignal(normalized);
        return explicitRecommend || budgetWithUseCase;
    }

    private static boolean isDraftCompletionIntent(String normalized, boolean hasDraftItems) {
        return hasDraftItems
                && containsAny(normalized, "채워", "완성", "나머지", "마저")
                && containsAny(normalized, "견적", "조합", "구성", "부품", "pc", "컴퓨터", "그래프");
    }

    private static boolean hasBuildUseCaseSignal(String normalized) {
        return containsAny(normalized,
                "영상", "편집", "프리미어", "블렌더", "렌더", "개발", "docker", "도커", "ide",
                "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크",
                "ai", "cuda", "로컬ai", "학습", "저소음", "조용", "컴팩트", "사무");
    }

    private static boolean isMissingMonitorContext(String normalized) {
        return containsAny(normalized, "모니터")
                && containsAny(normalized, "아직안", "안정", "못정", "미정", "모름");
    }

    private static boolean standaloneContext(Map<String, Object> body) {
        return objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()
                && objectMaps(body.get("currentBuilds")).isEmpty()
                && text(body.get("selectedCategory")) == null;
    }

    private static String semanticSignature(BuildChatIntent intent, String category, String partQuery, String budget) {
        return intent.name()
                + "|category=" + firstText(category, "ANY")
                + "|part=" + firstText(normalize(partQuery), "ANY")
                + "|budget=" + firstText(budget, "ANY");
    }

    private static String budgetSignature(String message) {
        BuildChatService.BudgetIntent budget = BuildChatService.budgetIntent(message);
        if (budget.budget() == null || budget.mode() == null) {
            return null;
        }
        return budget.mode() + ":" + budget.budget();
    }

    private static String normalize(Object value) {
        return value == null ? "" : value.toString().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static boolean containsAny(String normalized, String... needles) {
        for (String needle : needles) {
            if (normalized.contains(normalize(needle))) {
                return true;
            }
        }
        return false;
    }

    private static String firstText(String... values) {
        for (String value : values) {
            String text = text(value);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(Map.class::isInstance)
                    .map(item -> (Map<String, Object>) item)
                    .toList();
        }
        return List.of();
    }
}

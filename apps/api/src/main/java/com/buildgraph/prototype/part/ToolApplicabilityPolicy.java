package com.buildgraph.prototype.part;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Separates "tool executed" from "tool result is meaningful for the current draft".
 * Missing counterpart parts are pending selection, not compatibility failures.
 */
public final class ToolApplicabilityPolicy {
    private ToolApplicabilityPolicy() {
    }

    public static List<Map<String, Object>> applicableToolResults(List<Map<String, Object>> toolResults, List<ToolBuildPart> parts) {
        return toolResults.stream()
                .filter(result -> isApplicable(text(result.get("tool")), parts))
                .toList();
    }

    public static List<String> applicableCandidateTools(List<String> checkedTools, List<ToolBuildPart> parts) {
        return checkedTools.stream()
                .filter(tool -> isApplicable(tool, parts))
                .toList();
    }

    public static boolean isApplicable(String tool, List<ToolBuildPart> parts) {
        if (tool == null) {
            return false;
        }
        Set<String> categories = parts.stream()
                .map(ToolBuildPart::category)
                .filter(category -> category != null && !category.isBlank())
                .map(category -> category.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        return switch (tool) {
            // power는 판정 대상인 PSU가 담겨 있으면 유효하다 — GPU 부재는 예상 부하를 낮출 뿐이라
            // GPU 없이도 FAIL이면(예: 고TDP CPU + 저용량 PSU) GPU를 담으면 더 확실한 FAIL이다.
            // GPU까지 요구하면 그 진짜 FAIL이 '호환 가능'으로 둔갑한다. PSU 미선택만 '선택 대기'로 거른다.
            case "power" -> categories.contains("PSU");
            case "size" -> hasAnySizePair(categories);
            case "performance" -> categories.contains("CPU") && categories.contains("GPU");
            case "compatibility", "price" -> true;
            default -> false;
        };
    }

    private static boolean hasAnySizePair(Set<String> categories) {
        return categories.contains("CASE") && (
                categories.contains("GPU")
                        || categories.contains("COOLER")
                        || categories.contains("PSU")
                        || categories.contains("MOTHERBOARD")
        );
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}

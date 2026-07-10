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
            case "power" -> categories.contains("GPU") && categories.contains("PSU");
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

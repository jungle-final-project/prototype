package com.buildgraph.prototype.build;

import java.util.List;

public record BuildChatIntentDecision(
        BuildChatIntent intent,
        String confidence,
        String sideEffectRisk,
        String targetCategory,
        String partQuery,
        String preferredPath,
        String cachePolicy,
        String semanticConstraintSignature,
        List<String> ambiguityReasons,
        List<String> targetCategories
) {
    public BuildChatIntentDecision(
            BuildChatIntent intent,
            String confidence,
            String sideEffectRisk,
            String targetCategory,
            String partQuery,
            String preferredPath,
            String cachePolicy,
            String semanticConstraintSignature,
            List<String> ambiguityReasons
    ) {
        this(
                intent,
                confidence,
                sideEffectRisk,
                targetCategory,
                partQuery,
                preferredPath,
                cachePolicy,
                semanticConstraintSignature,
                ambiguityReasons,
                targetCategory == null ? List.of() : List.of(targetCategory)
        );
    }

    public BuildChatIntentDecision {
        ambiguityReasons = ambiguityReasons == null ? List.of() : List.copyOf(ambiguityReasons);
        targetCategories = targetCategories == null ? List.of() : List.copyOf(targetCategories);
    }

    public boolean isSemanticCacheEligible() {
        return "SEMANTIC_READ_ONLY".equals(cachePolicy)
                && "NONE".equals(sideEffectRisk)
                && semanticConstraintSignature != null
                && !semanticConstraintSignature.isBlank();
    }
}

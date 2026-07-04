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
        List<String> ambiguityReasons
) {
    public boolean isSemanticCacheEligible() {
        return "SEMANTIC_READ_ONLY".equals(cachePolicy)
                && "NONE".equals(sideEffectRisk)
                && semanticConstraintSignature != null
                && !semanticConstraintSignature.isBlank();
    }
}

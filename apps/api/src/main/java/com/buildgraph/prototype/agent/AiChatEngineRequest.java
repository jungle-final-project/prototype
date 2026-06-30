package com.buildgraph.prototype.agent;

import java.util.Map;

public record AiChatEngineRequest(
        String message,
        String surface,
        String selectedCategory,
        String buildId,
        String draftId,
        Map<String, Object> context,
        Long userInternalId
) {
}

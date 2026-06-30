package com.buildgraph.prototype.agent;

import java.util.Map;

public record AiChatAction(
        AiChatActionType type,
        String label,
        Map<String, Object> payload
) {
}

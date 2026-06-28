package com.buildgraph.prototype.agent;

import java.util.Map;

public record ToolInvocation(
        String id,
        String agentSessionId,
        String toolName,
        ToolStatus status,
        ConfidenceLevel confidence,
        String summary,
        Map<String, Object> requestPayload,
        Map<String, Object> resultPayload,
        int latencyMs,
        String createdAt
) {
}

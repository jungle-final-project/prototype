package com.buildgraph.prototype.agent;

import java.util.Map;

public record ToolResult(
        ToolStatus status,
        ConfidenceLevel confidence,
        String summary,
        Map<String, Object> details
) {
}

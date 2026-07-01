package com.buildgraph.prototype.agent;

import java.util.List;
import java.util.Map;

public record QuoteRequirementAnalysisResult(
        Map<String, Object> parsedContext,
        String agentSessionId,
        String agentSummary,
        List<String> evidenceIds
) {
}

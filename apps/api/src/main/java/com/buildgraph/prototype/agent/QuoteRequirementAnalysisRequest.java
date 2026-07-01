package com.buildgraph.prototype.agent;

import java.util.Map;

public record QuoteRequirementAnalysisRequest(
        String requirementId,
        String message,
        Map<String, Object> optionalInputs,
        Map<String, Object> fallbackContext
) {
}

package com.buildgraph.prototype.agent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface AgentDiagnosisChatLlmClient {
    Optional<Result> reply(Prompt prompt);

    record Prompt(
            String userMessage,
            Map<String, Object> rawDiagnosis,
            Map<String, Object> diagnosisContext,
            List<Map<String, Object>> recentMessages,
            Map<String, Object> ruleFallback,
            List<String> evidenceIds
    ) {
    }

    record Result(Map<String, Object> payload, String model, long latencyMs) {
    }
}

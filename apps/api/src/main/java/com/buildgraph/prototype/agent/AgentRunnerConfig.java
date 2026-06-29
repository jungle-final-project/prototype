package com.buildgraph.prototype.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AgentRunnerConfig {
    @Bean
    AgentRunner agentRunner(
            AgentTraceService agentTraceService,
            OpenAiResponsesClient openAiResponsesClient,
            @Value("${agent.runner.mode:deterministic}") String runnerMode
    ) {
        String normalizedMode = runnerMode == null ? "deterministic" : runnerMode.trim().toLowerCase();
        return switch (normalizedMode) {
            case "deterministic" -> new DeterministicAgentRunner(agentTraceService);
            case "llm" -> new LlmAgentRunner(agentTraceService, openAiResponsesClient);
            default -> throw new IllegalArgumentException("지원하지 않는 AGENT_RUNNER_MODE입니다: " + runnerMode);
        };
    }
}

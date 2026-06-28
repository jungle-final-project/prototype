package com.buildgraph.prototype.agent;

public record AgentState(
        AgentStatus from,
        AgentStatus to,
        String actor,
        String at,
        String reason
) {
}

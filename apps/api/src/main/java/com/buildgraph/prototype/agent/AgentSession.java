package com.buildgraph.prototype.agent;

import java.util.List;

public record AgentSession(
        String id,
        AgentStatus status,
        String summary,
        List<AgentState> stateTimeline,
        List<String> toolInvocationIds,
        List<String> evidenceIds
) {
}

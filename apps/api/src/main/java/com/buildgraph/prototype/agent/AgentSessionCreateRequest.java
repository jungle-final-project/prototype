package com.buildgraph.prototype.agent;

public record AgentSessionCreateRequest(
        String requirementId,
        String buildId,
        String asTicketId
) {
}

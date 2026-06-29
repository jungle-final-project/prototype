package com.buildgraph.prototype.agent;

import org.springframework.transaction.annotation.Transactional;

public class DeterministicAgentRunner implements AgentRunner {
    private final AgentTraceService agentTraceService;

    public DeterministicAgentRunner(AgentTraceService agentTraceService) {
        this.agentTraceService = agentTraceService;
    }

    @Override
    @Transactional
    public void run(String sessionId, AgentSessionRoot root, AgentRunProfile profile) {
        agentTraceService.recordRagEvidence(sessionId, AgentRunTraceDrafts.ragEvidence(root, profile));
        agentTraceService.advanceStatus(sessionId, AgentStatus.RAG_SEARCHED, "SYSTEM", "RAG evidence retrieved for " + profile.purpose());

        for (AgentToolInvocationDraft draft : AgentRunTraceDrafts.toolInvocations(root, profile)) {
            agentTraceService.recordToolInvocation(sessionId, draft);
        }
        agentTraceService.advanceStatus(sessionId, AgentStatus.TOOLS_CALLED, "SYSTEM", "tool invocations completed for " + profile.purpose());

        agentTraceService.updateSummary(sessionId, AgentRunTraceDrafts.deterministicSummary(profile));
        agentTraceService.advanceStatus(sessionId, AgentStatus.SUMMARY_READY, "SYSTEM", "summary generated for " + profile.summaryTarget());
        agentTraceService.advanceStatus(sessionId, AgentStatus.SUCCEEDED, "SYSTEM", "agent run completed");
    }
}

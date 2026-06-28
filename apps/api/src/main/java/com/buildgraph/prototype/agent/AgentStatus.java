package com.buildgraph.prototype.agent;

public enum AgentStatus {
    QUEUED,
    RUNNING,
    RAG_SEARCHED,
    TOOLS_CALLED,
    SUMMARY_READY,
    FALLBACK_READY,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

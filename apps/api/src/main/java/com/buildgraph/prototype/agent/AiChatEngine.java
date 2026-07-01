package com.buildgraph.prototype.agent;

public interface AiChatEngine {
    AiChatEngineResponse respond(AiChatEngineRequest request);

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request) {
        return respond(request);
    }

    QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request);
}

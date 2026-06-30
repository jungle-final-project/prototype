package com.buildgraph.prototype.agent;

public interface AiChatEngine {
    AiChatEngineResponse respond(AiChatEngineRequest request);

    QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request);
}

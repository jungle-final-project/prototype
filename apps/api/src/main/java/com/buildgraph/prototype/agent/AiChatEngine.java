package com.buildgraph.prototype.agent;

import java.util.Optional;

public interface AiChatEngine {
    AiChatEngineResponse respond(AiChatEngineRequest request);

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request) {
        return respond(request);
    }

    default AiChatEngineResponse respondLlmRequired(AiChatEngineRequest request, String requestedAiProfile) {
        return respondLlmRequired(request);
    }

    default AiChatEngineResponse explainBuildAssessment(AiChatEngineRequest request, String requestedAiProfile) {
        return respondLlmRequired(request, requestedAiProfile);
    }

    /**
     * 저정보 견적 요청에 명시된 사용자 대상/상황만 한 문장으로 요약한다.
     * 가격과 누락 질문은 서버가 확정하므로 구현체는 사실을 추가하지 않는다.
     */
    default Optional<String> acknowledgeLowInformationContext(
            AiChatEngineRequest request,
            String requestedAiProfile
    ) {
        return Optional.empty();
    }

    QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request);
}

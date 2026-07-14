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

    /**
     * 서버가 검증한 부품 후보와 변경 사실을 대화형 안내 문장으로만 다듬는다.
     * 구현체는 후보, 가격, 호환성 판단을 추가하거나 변경하지 않는다.
     */
    default Optional<String> explainVerifiedChangeAdvice(
            AiChatEngineRequest request,
            String requestedAiProfile
    ) {
        return Optional.empty();
    }

    /**
     * AS 상담 카드의 채팅 문구·요약·원인 후보를 증상 원문 기반으로 생성한다.
     * empty를 돌려주면 호출측이 카테고리별 정적 프로필로 폴백한다(데모 안전).
     */
    default Optional<SupportGuidanceDraft> draftSupportGuidance(
            String symptom,
            String symptomCategory,
            String requestedAiProfile
    ) {
        return Optional.empty();
    }

    QuoteRequirementAnalysisResult analyzeQuoteRequirement(QuoteRequirementAnalysisRequest request);
}

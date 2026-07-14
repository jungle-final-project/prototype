package com.buildgraph.prototype.agent;

import java.util.List;

/**
 * AS 상담 카드용 LLM 생성 초안. 증상 원문을 반영한 채팅 문구·요약·원인 후보만 담는다.
 * 제목/체크리스트/액션/고지 문구는 서버 정적 프로필이 계속 소유한다.
 */
public record SupportGuidanceDraft(
        String message,
        String summary,
        List<String> possibleCauses
) {
}

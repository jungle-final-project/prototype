package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildChatIntentRouterTest {
    private final BuildChatIntentRouter router = new BuildChatIntentRouter();

    @Test
    void classifiesMinimalPairMatrixWithReducedIntentSet() {
        List<Case> cases = List.of(
                // 견적 추천 (유지)
                c("5090 들어간 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("300만원 이하 RTX 5090 PC로 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                c("300만원 견적 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("3백만원 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("800만원으로 최고급 PC 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("QHD 배그용 컴퓨터 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                c("FHD 발로란트 240Hz 목표로 추천해줘", BuildChatIntent.BUILD_RECOMMEND),
                c("영상편집 + Docker + IDE 병행용으로 400만원 안쪽", BuildChatIntent.BUILD_RECOMMEND),
                c("오래 쓸 수 있게 업그레이드 여유 있는 구성", BuildChatIntent.BUILD_RECOMMEND),
                c("컴퓨터 하나 맞춰줘", BuildChatIntent.BUILD_RECOMMEND),
                // 그래프(드래프트) 기반 견적 완성 (유지)
                draft("지금 견적 기준으로 나머지 부품 채워줘", BuildChatIntent.BUILD_RECOMMEND),
                draft("이 그래프 구성 마저 완성해줘", BuildChatIntent.BUILD_RECOMMEND),
                // 성능 시뮬레이션 (유지) — "바꾸면"은 시뮬레이션이다
                draft("CPU를 9700X로 바꾸면?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("RAM 64GB로 바꾸면 성능 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("그래픽카드 5090으로 바꾸면 배그 QHD FPS 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("파워 1000W로 바꾸면 안정적이야?", BuildChatIntent.SIMULATE_REPLACEMENT),
                draft("쿨러를 수랭으로 바꾸면 온도 차이 어때?", BuildChatIntent.SIMULATE_REPLACEMENT),
                c("GPU 바꾸면 성능 어떻게 돼?", BuildChatIntent.SIMULATE_REPLACEMENT),
                // 명확화 (유지)
                c("게임용인데 모니터는 아직 안 정했어", BuildChatIntent.ASK_CLARIFICATION),
                c("아무거나 좋은 걸로", BuildChatIntent.ASK_CLARIFICATION),
                // 화면 이동/필터/상세 — 제거된 기능, 고정 안내
                c("GPU 보여줘", BuildChatIntent.UNSUPPORTED),
                c("그래픽카드 화면 열어줘", BuildChatIntent.UNSUPPORTED),
                c("메인보드 목록 보여줘", BuildChatIntent.UNSUPPORTED),
                c("5090 보여줘", BuildChatIntent.UNSUPPORTED),
                c("9950X3D 보여줘", BuildChatIntent.UNSUPPORTED),
                c("리안리 216 케이스 상세페이지 보여줘", BuildChatIntent.UNSUPPORTED),
                c("AMD 라이젠9-6세대 9950X3D 그래니트 릿지 정품(멀티팩) 상세페이지로 이동해", BuildChatIntent.UNSUPPORTED),
                c("내 견적함 열어줘", BuildChatIntent.UNSUPPORTED),
                c("구매하기 화면으로 이동", BuildChatIntent.UNSUPPORTED),
                // 단일 부품 추천 — 제거된 기능 (그래프 노드 담당)
                c("5090 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("그래픽카드 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("고성능 GPU 추천해줘", BuildChatIntent.UNSUPPORTED),
                c("SSD 추천해줘", BuildChatIntent.UNSUPPORTED),
                // 장바구니 조작 대화 — 제거된 기능. "바꿔줘"는 시뮬레이션이 아니다
                draft("GPU 빼줘", BuildChatIntent.UNSUPPORTED),
                draft("그래픽카드 삭제", BuildChatIntent.UNSUPPORTED),
                draft("RAM 64GB로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("램 수량 2개로 변경", BuildChatIntent.UNSUPPORTED),
                draft("CPU를 9700X로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("그래픽카드 5090으로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("GPU 더 좋은 걸로 바꿔줘", BuildChatIntent.UNSUPPORTED),
                draft("메인보드 MSI 걸로 맞춰줘", BuildChatIntent.UNSUPPORTED),
                draft("케이스 리안리 216 모델꺼로 맞춰줘", BuildChatIntent.UNSUPPORTED),
                // 일반 설명/상담 — 제거된 기능
                c("이 견적 왜 좋아?", BuildChatIntent.UNSUPPORTED),
                c("지금 견적 호환 괜찮아?", BuildChatIntent.UNSUPPORTED),
                c("예산 없이 끝판왕으로", BuildChatIntent.UNSUPPORTED)
        );

        assertThat(cases).hasSizeGreaterThanOrEqualTo(40);
        for (Case item : cases) {
            BuildChatIntentDecision decision = router.decide(item.request(), item.message());
            assertThat(decision.intent()).as(item.message()).isEqualTo(item.intent());
            assertThat(decision.sideEffectRisk()).as(item.message()).isEqualTo("NONE");
        }
    }

    @Test
    void semanticCacheEligibilityIsLimitedToStandaloneBuildRecommendations() {
        BuildChatIntentDecision build = router.decide(Map.of("message", "300만원 견적 추천해줘"), "300만원 견적 추천해줘");
        BuildChatIntentDecision partRecommend = router.decide(Map.of("message", "고성능 GPU 추천해줘"), "고성능 GPU 추천해줘");
        BuildChatIntentDecision mutation = router.decide(draftRequest("GPU 빼줘"), "GPU 빼줘");
        BuildChatIntentDecision simulation = router.decide(draftRequest("GPU를 5090으로 바꾸면 FPS 어때?"), "GPU를 5090으로 바꾸면 FPS 어때?");
        BuildChatIntentDecision draftCompletion = router.decide(draftRequest("지금 견적 나머지 채워줘"), "지금 견적 나머지 채워줘");

        assertThat(build.isSemanticCacheEligible()).isTrue();
        assertThat(partRecommend.isSemanticCacheEligible()).isFalse();
        assertThat(mutation.isSemanticCacheEligible()).isFalse();
        assertThat(simulation.isSemanticCacheEligible()).isFalse();
        assertThat(draftCompletion.isSemanticCacheEligible()).isFalse();
    }

    private static Case c(String message, BuildChatIntent intent) {
        return new Case(message, Map.of("message", message), intent);
    }

    private static Case draft(String message, BuildChatIntent intent) {
        return new Case(message, draftRequest(message), intent);
    }

    private static Map<String, Object> draftRequest(String message) {
        return Map.of(
                "message", message,
                "currentQuoteDraft", Map.of("items", List.of(
                        Map.of("partId", "gpu-1", "category", "GPU", "name", "RTX 5080", "quantity", 1),
                        Map.of("partId", "cpu-1", "category", "CPU", "name", "Ryzen 7", "quantity", 1),
                        Map.of("partId", "ram-1", "category", "RAM", "name", "DDR5 32GB", "quantity", 1),
                        Map.of("partId", "ssd-1", "category", "STORAGE", "name", "NVMe 1TB", "quantity", 1),
                        Map.of("partId", "psu-1", "category", "PSU", "name", "850W", "quantity", 1),
                        Map.of("partId", "case-1", "category", "CASE", "name", "Mid Tower", "quantity", 1),
                        Map.of("partId", "cooler-1", "category", "COOLER", "name", "Air Cooler", "quantity", 1)
                ))
        );
    }

    private record Case(String message, Map<String, Object> request, BuildChatIntent intent) {
    }
}

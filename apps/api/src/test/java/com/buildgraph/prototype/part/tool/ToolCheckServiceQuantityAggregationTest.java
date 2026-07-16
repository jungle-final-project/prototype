package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 감사 P1-3②: 전력·총액 합산이 수량과 동종 복수 상품을 반영하는지 검증.
 * 기존에는 byCategory(카테고리당 1개)로 접힌 목록에 수량 미가중이라
 * SSD 3개·RAM 2킷 견적의 부하가 1개분으로 계산됐다.
 */
class ToolCheckServiceQuantityAggregationTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart part(String category, String id, Map<String, Object> attributes, int quantity) {
        return new ToolBuildPart(1L, id, category, "테스트 " + category, "테스트", 100000, attributes, quantity);
    }

    private Map<String, Object> power(List<ToolBuildPart> parts) {
        return service.checkBuild(parts, 3_000_000).stream()
                .filter(result -> "power".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("details");
    }

    @Test
    void estimatedLoadWeighsQuantity() {
        // RAM 10W 상품을 quantity 4로 담으면 부하도 4배(40W)여야 한다 — 1개분(10W)이 아니라.
        Map<String, Object> single = power(List.of(
                part("PSU", "psu-id", Map.of("capacityW", 850), 1),
                part("RAM", "ram-id", Map.of("wattage", 10), 1)
        ));
        Map<String, Object> quad = power(List.of(
                part("PSU", "psu-id", Map.of("capacityW", 850), 1),
                part("RAM", "ram-id", Map.of("wattage", 10), 4)
        ));

        int singleLoad = (int) details(single).get("estimatedContinuousLoadW");
        int quadLoad = (int) details(quad).get("estimatedContinuousLoadW");
        assertThat(quadLoad - singleLoad).isEqualTo(30);
    }

    @Test
    void estimatedLoadCountsEveryRowOfSameCategory() {
        // 같은 카테고리 복수 상품(SSD 3종)은 전부 계상돼야 한다 — byCategory 접힘으로 1개만 세면 안 된다.
        Map<String, Object> one = power(List.of(
                part("PSU", "psu-id", Map.of("capacityW", 850), 1),
                part("STORAGE", "ssd-1", Map.of("wattage", 8), 1)
        ));
        Map<String, Object> three = power(List.of(
                part("PSU", "psu-id", Map.of("capacityW", 850), 1),
                part("STORAGE", "ssd-1", Map.of("wattage", 8), 1),
                part("STORAGE", "ssd-2", Map.of("wattage", 8), 1),
                part("STORAGE", "ssd-3", Map.of("wattage", 8), 1)
        ));

        int oneLoad = (int) details(one).get("estimatedContinuousLoadW");
        int threeLoad = (int) details(three).get("estimatedContinuousLoadW");
        assertThat(threeLoad - oneLoad).isEqualTo(16);
    }
}

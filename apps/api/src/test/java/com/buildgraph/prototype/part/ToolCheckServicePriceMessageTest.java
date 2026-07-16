package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** price 문구 — WARN/FAIL을 구분하고 예산·총액·차액 실값을 말한다(판정 기준 자체는 기존 유지). */
class ToolCheckServicePriceMessageTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart gpu(int price) {
        return new ToolBuildPart(40L, "gpu-id", "GPU", "테스트 GPU", "NVIDIA", price, Map.of("wattage", 300));
    }

    private Map<String, Object> price(List<ToolBuildPart> parts, int budget) {
        return service.checkBuild(parts, budget).stream()
                .filter(result -> "price".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void withinBudgetKeepsPassMessage() {
        Map<String, Object> result = price(List.of(gpu(900_000)), 1_000_000);

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(result.get("summary")).isEqualTo("저장된 현재가 기준 예산 안에 들어옵니다.");
    }

    @Test
    void warnWithinGraceBandStatesAmounts() {
        // 8% 유예 이내 초과 — WARN 문구가 FAIL과 동일하면 안 되고 차액이 있어야 한다.
        Map<String, Object> result = price(List.of(gpu(1_080_000)), 1_000_000);

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo(
                "저장된 현재가 기준 총액 1,080,000원이 예산 1,000,000원을 80,000원 초과합니다(예산의 8% 이내)");
    }

    @Test
    void failBeyondGraceBandStatesAmounts() {
        Map<String, Object> result = price(List.of(gpu(1_200_000)), 1_000_000);

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo(
                "저장된 현재가 기준 총액 1,200,000원이 예산 1,000,000원을 200,000원 초과합니다");
    }
}

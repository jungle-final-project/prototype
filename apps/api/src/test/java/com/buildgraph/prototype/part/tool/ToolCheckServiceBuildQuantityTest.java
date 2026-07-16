package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * buildId 경로(partsByBuildId) 계약 고정 — build_items에는 quantity 컬럼이 없다
 * (DB_SCHEMA §build_items: 수량은 price에 line total로만 반영). 그래서 이 경로는
 * 1개분으로 계산되고, SELECT가 존재하지 않는 bi.quantity를 참조하면 실서버가 500이 난다.
 * 수량 반영 검사는 AI_BUILD(BuildGraphServiceTest)·QUOTE_DRAFT 경로가 담당한다.
 */
class ToolCheckServiceBuildQuantityTest {
    private static Map<String, Object> row(
            long internalId,
            String publicId,
            String category,
            int price,
            Map<String, Object> attributes
    ) {
        // 실제 build_items 조회 결과와 동일하게 quantity 키 자체가 없다.
        return Map.of(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", "테스트 " + category,
                "manufacturer", "테스트",
                "price", price,
                "attributes", attributes
        );
    }

    @Test
    void buildPathSelectsNoQuantityColumnAndCountsSingleUnits() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM build_items"), any(Object[].class)))
                .thenReturn(List.of(
                        row(1L, "board-public-id", "MOTHERBOARD", 250_000,
                                Map.of("socket", "AM5", "memoryType", "DDR5", "memorySlots", 2)),
                        row(2L, "ram-public-id", "RAM", 150_000,
                                Map.of("memoryType", "DDR5", "moduleCount", 2))
                ));
        ToolCheckService service = new ToolCheckService(jdbcTemplate);

        Map<String, Object> result = service.checkTool("compatibility",
                Map.of("buildId", "11111111-1111-4111-8111-111111111111"));

        // 스키마에 없는 컬럼을 조회하지 않는다 — 있으면 실서버는 BadSqlGrammarException 500.
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).doesNotContain("quantity");

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) result.get("details");
        // quantity 미상 → 1개분: 2모듈 킷 1세트 = 스틱 2개 ≤ 슬롯 2개.
        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details.get("ramSticksTotal")).isEqualTo(2);
    }

    @Test
    void buildPathWeighsLoadAsSingleUnits() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(contains("FROM build_items"), any(Object[].class)))
                .thenReturn(List.of(
                        row(1L, "psu-public-id", "PSU", 180_000, Map.of("capacityW", 850)),
                        row(2L, "ssd-public-id", "STORAGE", 120_000,
                                Map.of("interface", "SATA", "wattage", 8))
                ));
        ToolCheckService service = new ToolCheckService(jdbcTemplate);

        Map<String, Object> power = service.checkTool("power",
                Map.of("buildId", "11111111-1111-4111-8111-111111111111"));

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) power.get("details");
        // SSD 8W × 1개 + 기본 60W = 68W — 빌드 경로는 수량 정보가 없어 1개분 계산이 계약이다.
        assertThat(details.get("estimatedContinuousLoadW")).isEqualTo(68);
    }
}

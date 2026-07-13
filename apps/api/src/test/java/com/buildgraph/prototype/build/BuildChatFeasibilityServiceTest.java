package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class BuildChatFeasibilityServiceTest {
    @Test
    void gpuValueCandidatesUseBenchmarkPerPriceInsteadOfHighestPrice() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of(
                Map.of(
                        "id", "gpu-value",
                        "name", "가성비 GPU",
                        "price", 500_000,
                        "capacity_gb", 0,
                        "vram_gb", 16,
                        "wattage_w", 200)
        ));
        BuildChatFeasibilityService service = new BuildChatFeasibilityService(jdbcTemplate);

        List<BuildChatFeasibilityService.PartOption> options = service.bestValueFirst("GPU", 50);

        assertThat(options).extracting(BuildChatFeasibilityService.PartOption::partId)
                .containsExactly("gpu-value");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue())
                .contains("benchmark_summaries")
                .contains("greatest(p.price, 1)")
                .contains("DESC, price ASC");
    }

    @Test
    void categoriesWithoutObjectivePerformanceEvidenceUsePriceOrderForValueRequest() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForList(any(String.class), any(Object[].class))).thenReturn(List.of());
        BuildChatFeasibilityService service = new BuildChatFeasibilityService(jdbcTemplate);

        service.bestValueFirst("CASE", 50);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("ORDER BY price ASC, name ASC");
    }
}

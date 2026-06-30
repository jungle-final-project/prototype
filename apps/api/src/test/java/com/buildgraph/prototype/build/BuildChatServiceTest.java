package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.part.ToolCheckService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class BuildChatServiceTest {
    @Test
    void parsesBudgetWonFromCommonKoreanInputs() {
        assertThat(BuildChatService.parseBudgetWon("200만원 PC 추천")).isEqualTo(2_000_000);
        assertThat(BuildChatService.parseBudgetWon("300만원대로 맞춰줘")).isEqualTo(3_000_000);
        assertThat(BuildChatService.parseBudgetWon("2,000,000원 안에서")).isEqualTo(2_000_000);
    }

    @Test
    void detectsPartQuestionCategories() {
        assertThat(BuildChatService.detectPartCategory("GPU 추천해줘")).isEqualTo("GPU");
        assertThat(BuildChatService.detectPartCategory("CPU는 뭐가 좋아?")).isEqualTo("CPU");
        assertThat(BuildChatService.detectPartCategory("쿨러 추천")).isEqualTo("COOLER");
    }

    @Test
    void activePartQueryKeepsWhitespaceBetweenToolReadyFilterAndOrderBy() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        ToolCheckService toolCheckService = mock(ToolCheckService.class);
        BuildChatService service = new BuildChatService(jdbcTemplate, toolCheckService);
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenAnswer(invocation -> {
            String category = invocation.getArgument(1);
            return List.of(partRow(category));
        });
        when(toolCheckService.checkBuild(anyList(), anyInt())).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "예산 안에 들어옵니다."
        )));

        service.chat(Map.of("message", "200만원 PC 추천"));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, atLeastOnce()).queryForList(sqlCaptor.capture(), anyString());
        assertThat(sqlCaptor.getAllValues()).noneMatch(sql -> sql.contains("trueORDER"));
    }

    private static Map<String, Object> partRow(String category) {
        return Map.of(
                "internal_id", 1L,
                "id", "00000000-0000-4000-8000-000000000101",
                "category", category,
                "name", category + " test part",
                "manufacturer", "BuildGraph",
                "price", 100_000,
                "attributes", "{\"toolReady\":true}"
        );
    }
}

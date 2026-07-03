package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.AgentJobPublisher;
import com.buildgraph.prototype.agent.AgentTraceService;
import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class BuildQueryServiceFromChatTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            "2026-06-30T00:00:00Z"
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final ToolCheckService toolCheckService = mock(ToolCheckService.class);
    private final BuildQueryService service = new BuildQueryService(
            jdbcTemplate,
            mock(AgentTraceService.class),
            mock(AgentJobPublisher.class),
            mock(AiChatEngine.class),
            toolCheckService
    );

    @Test
    void saveFromChatCreatesRequirementAndBuildWithDisplayedLinePrices() {
        when(jdbcTemplate.queryForList(contains("FROM parts"), eq("00000000-0000-4000-8000-000000000101")))
                .thenReturn(List.of(part(101L, "00000000-0000-4000-8000-000000000101", "CPU", "Ryzen 7", 500000)));
        when(jdbcTemplate.queryForList(contains("FROM parts"), eq("00000000-0000-4000-8000-000000000201")))
                .thenReturn(List.of(part(201L, "00000000-0000-4000-8000-000000000201", "GPU", "RTX 5070", 900000)));
        when(toolCheckService.checkBuild(anyList(), eq(2_220_000))).thenReturn(List.of(Map.of(
                "tool", "price",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "표시 가격 기준 예산 안에 들어옵니다."
        )));
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO requirements"),
                eq(Long.class),
                eq(USER.internalId()),
                eq("200만원 PC 추천"),
                eq(2_000_000),
                eq(""),
                any()
        )).thenReturn(9001L);
        when(jdbcTemplate.queryForObject(
                contains("INSERT INTO builds"),
                eq(String.class),
                eq(9001L),
                eq("200만원 균형형"),
                eq(2_220_000),
                eq("HIGH"),
                any()
        )).thenReturn("00000000-0000-4000-8000-000000009001");

        Map<String, Object> result = service.saveFromChat(request(), USER);

        assertThat(result).containsEntry("id", "00000000-0000-4000-8000-000000009001");
        verify(jdbcTemplate).update(
                contains("INSERT INTO build_items"),
                eq(101L),
                eq("CPU"),
                eq(420000),
                eq("00000000-0000-4000-8000-000000009001")
        );
        verify(jdbcTemplate).update(
                contains("INSERT INTO build_items"),
                eq(201L),
                eq("GPU"),
                eq(1_800_000),
                eq("00000000-0000-4000-8000-000000009001")
        );
    }

    @Test
    void saveFromChatRejectsPartCategoryMismatchBeforeCreatingRequirement() {
        when(jdbcTemplate.queryForList(contains("FROM parts"), eq("00000000-0000-4000-8000-000000000101")))
                .thenReturn(List.of(part(101L, "00000000-0000-4000-8000-000000000101", "CPU", "Ryzen 7", 500000)));

        Map<String, Object> request = MockData.map(
                "sourceBuildId", "ai-build-mismatch",
                "lastUserMessage", "GPU 추천",
                "build", MockData.map(
                        "id", "ai-build-mismatch",
                        "title", "잘못된 GPU 추천",
                        "summary", "category mismatch",
                        "totalPrice", 420000,
                        "confidence", "HIGH",
                        "items", List.of(MockData.map(
                                "partId", "00000000-0000-4000-8000-000000000101",
                                "category", "GPU",
                                "quantity", 1,
                                "price", 420000
                        ))
                )
        );

        assertThatThrownBy(() -> service.saveFromChat(request, USER))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("카테고리");
                });
        verify(jdbcTemplate, never()).queryForObject(contains("INSERT INTO requirements"), eq(Long.class), any());
    }

    private static Map<String, Object> request() {
        return MockData.map(
                "sourceBuildId", "ai-budget-2000000-balanced",
                "lastUserMessage", "200만원 PC 추천",
                "build", MockData.map(
                        "id", "ai-budget-2000000-balanced",
                        "tier", "balanced",
                        "title", "200만원 균형형",
                        "summary", "게임과 개발을 균형 있게 반영했습니다.",
                        "totalPrice", 2_220_000,
                        "budgetWon", 2_000_000,
                        "confidence", "HIGH",
                        "items", List.of(
                                MockData.map(
                                        "partId", "00000000-0000-4000-8000-000000000101",
                                        "category", "CPU",
                                        "quantity", 1,
                                        "price", 420000
                                ),
                                MockData.map(
                                        "partId", "00000000-0000-4000-8000-000000000201",
                                        "category", "GPU",
                                        "quantity", 2,
                                        "price", 900000
                                )
                        )
                )
        );
    }

    private static Map<String, Object> part(long internalId, String publicId, String category, String name, int price) {
        return MockData.map(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", Map.of()
        );
    }
}

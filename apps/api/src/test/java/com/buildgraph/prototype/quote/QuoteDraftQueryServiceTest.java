package com.buildgraph.prototype.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class QuoteDraftQueryServiceTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final QuoteDraftQueryService quoteDraftQueryService = new QuoteDraftQueryService(jdbcTemplate, currentUserService);

    @Test
    void applyAiBuildFailsBeforeDraftMutationWhenPartIdIsInvalid() {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("missing-part-id"))).thenReturn(List.of());

        assertThatThrownBy(() -> quoteDraftQueryService.applyAiBuild(USER_TOKEN, Map.of(
                "conflictPolicy", "REPLACE",
                "items", List.of(Map.of(
                        "partId", "missing-part-id",
                        "category", "GPU",
                        "quantity", 1
                ))
        )))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(jdbcTemplate, never()).queryForList(anyString(), eq(1004L));
    }

    @Test
    void applyAiBuildReplacesRequestedCategoriesAndReturnsQuoteDraft() {
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(currentUser());
        when(jdbcTemplate.queryForList(anyString(), eq("part-cpu-ai"))).thenReturn(List.of(part("part-cpu-ai", 101L, "CPU", "Ryzen AI CPU", 420000)));
        when(jdbcTemplate.queryForList(anyString(), eq("part-gpu-ai"))).thenReturn(List.of(part("part-gpu-ai", 201L, "GPU", "RTX AI GPU", 890000)));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L))).thenReturn(List.of(activeDraft()), List.of(activeDraft()));
        when(jdbcTemplate.queryForList(anyString(), eq(700L))).thenReturn(List.of(
                draftItem("draft-item-cpu", "part-cpu-ai", "CPU", "Ryzen AI CPU", 420000),
                draftItem("draft-item-gpu", "part-gpu-ai", "GPU", "RTX AI GPU", 890000)
        ));

        Map<String, Object> draft = quoteDraftQueryService.applyAiBuild(USER_TOKEN, Map.of(
                "buildId", "ai-2000000-balanced",
                "conflictPolicy", "REPLACE",
                "items", List.of(
                        Map.of("partId", "part-cpu-ai", "category", "CPU", "quantity", 1),
                        Map.of("partId", "part-gpu-ai", "category", "GPU", "quantity", 1)
                )
        ));

        assertThat(draft.get("status")).isEqualTo("ACTIVE");
        assertThat(draft.get("totalPrice")).isEqualTo(1_310_000);
        assertThat(draft.get("itemCount")).isEqualTo(2);
        assertThat((List<?>) draft.get("items")).hasSize(2);
    }

    private CurrentUserService.CurrentUser currentUser() {
        return new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Demo User",
                "USER",
                "2026-06-30T00:00:00Z"
        );
    }

    private static Map<String, Object> activeDraft() {
        return Map.of(
                "internal_id", 700L,
                "id", "draft-public-id",
                "status", "ACTIVE",
                "name", "셀프 견적",
                "created_at", "2026-06-30T00:00:00Z",
                "updated_at", "2026-06-30T00:00:00Z"
        );
    }

    private static Map<String, Object> part(String publicId, long internalId, String category, String name, int price) {
        return Map.of(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "price", price,
                "attributes", "{}"
        );
    }

    private static Map<String, Object> draftItem(String rowId, String partId, String category, String name, int price) {
        return MockData.map(
                "id", rowId,
                "part_id", partId,
                "category", category,
                "name", name,
                "manufacturer", "BuildGraph",
                "current_price", price,
                "attributes", "{}",
                "quantity", 1,
                "unit_price_at_add", price,
                "created_at", "2026-06-30T00:00:00Z",
                "updated_at", "2026-06-30T00:00:00Z"
        );
    }
}

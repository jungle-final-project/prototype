package com.buildgraph.prototype.price;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class PriceQueryServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            "2026-06-30T00:00:00Z"
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final PriceQueryService service = new PriceQueryService(
            jdbcTemplate,
            mock(PriceJobPublisher.class)
    );

    @Test
    void createAlertRejectsMalformedPartIdBeforeQueryingPostgres() {
        Map<String, Object> request = MockData.map(
                "partId", "인텔",
                "targetPrice", 700000
        );

        assertThatThrownBy(() -> service.createAlert(request, USER))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).contains("partId");
                });
        verifyNoInteractions(jdbcTemplate);
    }
}

package com.buildgraph.prototype.recommendation;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class RecommendationLearningServiceTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Test
    void publicEventApiRejectsAdminFeedbackTypes() {
        RecommendationLearningService service = new RecommendationLearningService(org.mockito.Mockito.mock(JdbcTemplate.class));

        assertThatThrownBy(() -> service.recordEvent(Map.of("eventType", "ADMIN_PROMOTE"), USER))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(error -> ((ResponseStatusException) error).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }
}

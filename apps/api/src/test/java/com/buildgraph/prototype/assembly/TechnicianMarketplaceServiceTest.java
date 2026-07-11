package com.buildgraph.prototype.assembly;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class TechnicianMarketplaceServiceTest {
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CurrentUserService currentUserService = mock(CurrentUserService.class);
    private final TechnicianMarketplaceService service = new TechnicianMarketplaceService(jdbcTemplate, currentUserService);

    @Test
    void adminAccountCannotApplyAsExternalTechnician() {
        when(currentUserService.requireUser("Bearer admin")).thenReturn(new CurrentUserService.CurrentUser(
                1L, "admin-public-id", "admin@example.com", "Admin", "ADMIN", null));

        assertThatThrownBy(() -> service.apply("Bearer admin", Map.of()))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException apiException = (ApiException) error;
                    org.assertj.core.api.Assertions.assertThat(apiException.status()).isEqualTo(HttpStatus.FORBIDDEN);
                    org.assertj.core.api.Assertions.assertThat(apiException.code()).isEqualTo("FORBIDDEN");
                });

        verifyNoInteractions(jdbcTemplate);
    }
}

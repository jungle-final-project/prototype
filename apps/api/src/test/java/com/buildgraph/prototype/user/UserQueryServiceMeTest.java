package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;

class UserQueryServiceMeTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PasswordService passwordService = new PasswordService();
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            "test-buildgraph-jwt-secret-change-me-2026",
            "buildgraph-api-test",
            Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-06-29T09:00:00Z"), ZoneOffset.UTC)
    );
    private final UserQueryService userQueryService = new UserQueryService(jdbcTemplate, passwordService, jwtTokenService);

    @Test
    void meReturnsUserForValidJwt() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(userRow()));
        String token = jwtTokenService.issueAccessToken(user());

        Map<String, Object> response = userQueryService.me("Bearer " + token);

        assertThat(response).containsEntry("id", "00000000-0000-4000-8000-000000001004");
        assertThat(response).containsEntry("email", "user@example.com");
        assertThat(response).containsEntry("role", "USER");
    }

    @Test
    void meRejectsMissingAuthorization() {
        assertThatThrownBy(() -> userQueryService.me(null))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void meRejectsInvalidBearerToken() {
        assertThatThrownBy(() -> userQueryService.me("Bearer invalid-token"))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception ->
                        assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED)
                );
        verifyNoInteractions(jdbcTemplate);
    }

    private Map<String, Object> user() {
        return Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        );
    }

    private Map<String, Object> userRow() {
        return Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        );
    }
}

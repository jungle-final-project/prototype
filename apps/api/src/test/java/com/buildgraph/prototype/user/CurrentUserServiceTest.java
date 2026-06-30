package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

class CurrentUserServiceTest {
    private static final String TEST_SECRET = "test-buildgraph-jwt-secret-change-me-2026";
    private static final Instant NOW = Instant.parse("2026-06-29T09:00:00Z");
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            TEST_SECRET,
            "buildgraph-api-test",
            Duration.ofMinutes(15),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );
    private final CurrentUserService currentUserService = new CurrentUserService(jdbcTemplate, jwtTokenService);

    @Test
    void requireUserResolvesJwtSubjectFromUsersTable() {
        String token = jwtTokenService.issueAccessToken(userClaim("00000000-0000-4000-8000-000000001004", "USER"));
        when(jdbcTemplate.queryForList(anyString(), eq("00000000-0000-4000-8000-000000001004"))).thenReturn(List.of(Map.of(
                "internal_id", 1004L,
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER",
                "created_at", "2026-06-29T09:05:00Z"
        )));

        CurrentUserService.CurrentUser user = currentUserService.requireUser("Bearer " + token);

        assertThat(user.internalId()).isEqualTo(1004L);
        assertThat(user.id()).isEqualTo("00000000-0000-4000-8000-000000001004");
        assertThat(user.email()).isEqualTo("user@example.com");
        assertThat(user.role()).isEqualTo("USER");
    }

    @Test
    void requireAdminRejectsUserRoleWithForbidden() {
        String token = jwtTokenService.issueAccessToken(userClaim("00000000-0000-4000-8000-000000001004", "USER"));
        when(jdbcTemplate.queryForList(anyString(), eq("00000000-0000-4000-8000-000000001004"))).thenReturn(List.of(Map.of(
                "internal_id", 1004L,
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER",
                "created_at", "2026-06-29T09:05:00Z"
        )));

        assertThatThrownBy(() -> currentUserService.requireAdmin("Bearer " + token))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void requireUserRejectsLegacyDemoToken() {
        assertThatThrownBy(() -> currentUserService.requireUser("Bearer demo-access-user"))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        verifyNoInteractions(jdbcTemplate);
    }

    private Map<String, Object> userClaim(String id, String role) {
        return Map.of(
                "id", id,
                "email", role.equals("ADMIN") ? "admin@example.com" : "user@example.com",
                "name", role.equals("ADMIN") ? "Admin User" : "Demo User",
                "role", role
        );
    }
}

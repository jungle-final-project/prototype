package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
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

/** requireUser 사용자 캐시(TTL>0) 동작 고정 — 반복 요청 1쿼리, evict 후 재조회, 미존재 사용자 부정 캐시 없음. */
class CurrentUserServiceCacheTest {
    private static final String TEST_SECRET = "test-buildgraph-jwt-secret-change-me-2026";
    private static final String SUBJECT = "00000000-0000-4000-8000-000000001004";
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            TEST_SECRET,
            "buildgraph-api-test",
            Duration.ofMinutes(15),
            Clock.fixed(Instant.parse("2026-06-29T09:00:00Z"), ZoneOffset.UTC)
    );
    // TTL 30초 — 프로덕션 기본값과 같은 캐시 켜짐 경로.
    private final CurrentUserService currentUserService = new CurrentUserService(jdbcTemplate, jwtTokenService, 30L);

    @Test
    void repeatedRequestsHitUsersTableOnlyOnce() {
        String token = jwtTokenService.issueAccessToken(userClaim());
        when(jdbcTemplate.queryForList(anyString(), eq(SUBJECT))).thenReturn(userRows());

        CurrentUserService.CurrentUser first = currentUserService.requireUser("Bearer " + token);
        CurrentUserService.CurrentUser second = currentUserService.requireUser("Bearer " + token);

        assertThat(first.internalId()).isEqualTo(1004L);
        assertThat(second.internalId()).isEqualTo(1004L);
        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(SUBJECT));
    }

    @Test
    void evictCachedUserForcesReload() {
        String token = jwtTokenService.issueAccessToken(userClaim());
        when(jdbcTemplate.queryForList(anyString(), eq(SUBJECT))).thenReturn(userRows());

        currentUserService.requireUser("Bearer " + token);
        currentUserService.evictCachedUser(SUBJECT);
        currentUserService.requireUser("Bearer " + token);

        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(SUBJECT));
    }

    @Test
    void unknownUserIsNotNegativelyCached() {
        String token = jwtTokenService.issueAccessToken(userClaim());
        // 첫 조회는 미존재(빈 결과) → 401. 이후 사용자가 생기면 캐시에 남은 부정 결과 없이 정상 조회돼야 한다.
        when(jdbcTemplate.queryForList(anyString(), eq(SUBJECT)))
                .thenReturn(List.of())
                .thenReturn(userRows());

        assertThatThrownBy(() -> currentUserService.requireUser("Bearer " + token))
                .isInstanceOf(ResponseStatusException.class)
                .extracting("statusCode")
                .isEqualTo(HttpStatus.UNAUTHORIZED);

        CurrentUserService.CurrentUser user = currentUserService.requireUser("Bearer " + token);
        assertThat(user.internalId()).isEqualTo(1004L);
    }

    private static List<Map<String, Object>> userRows() {
        return List.of(Map.of(
                "internal_id", 1004L,
                "id", SUBJECT,
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER",
                "created_at", "2026-06-29T09:05:00Z"
        ));
    }

    private Map<String, Object> userClaim() {
        return Map.of(
                "id", SUBJECT,
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        );
    }
}

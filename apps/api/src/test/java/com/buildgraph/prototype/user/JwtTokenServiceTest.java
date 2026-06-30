package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JwtTokenServiceTest {
    private static final String TEST_SECRET = "test-buildgraph-jwt-secret-change-me-2026";
    private static final Instant NOW = Instant.parse("2026-06-29T09:00:00Z");
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            TEST_SECRET,
            "buildgraph-api-test",
            Duration.ofMinutes(15),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void issueAccessTokenReturnsJwtWithUserClaims() throws Exception {
        String token = jwtTokenService.issueAccessToken(user());

        assertThat(token).isNotBlank();
        assertThat(token.split("\\.")).hasSize(3);

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getJWTClaimsSet().getIssuer()).isEqualTo("buildgraph-api-test");
        assertThat(jwt.getJWTClaimsSet().getSubject()).isEqualTo("00000000-0000-4000-8000-000000001004");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("email")).isEqualTo("user@example.com");
        assertThat(jwt.getJWTClaimsSet().getStringClaim("role")).isEqualTo("USER");
    }

    @Test
    void issueAccessTokenSetsExpiration() throws Exception {
        String token = jwtTokenService.issueAccessToken(user());

        SignedJWT jwt = SignedJWT.parse(token);
        assertThat(jwt.getJWTClaimsSet().getIssueTime()).isEqualTo(Date.from(NOW));
        assertThat(jwt.getJWTClaimsSet().getExpirationTime()).isEqualTo(Date.from(NOW.plus(Duration.ofMinutes(15))));
    }

    @Test
    void verifyAccessTokenReturnsClaimsForValidToken() throws Exception {
        String token = jwtTokenService.issueAccessToken(user());

        JWTClaimsSet claims = jwtTokenService.verifyAccessToken(token);

        assertThat(claims.getSubject()).isEqualTo("00000000-0000-4000-8000-000000001004");
        assertThat(claims.getStringClaim("email")).isEqualTo("user@example.com");
        assertThat(claims.getStringClaim("role")).isEqualTo("USER");
    }

    @Test
    void verifyAccessTokenRejectsExpiredToken() {
        JwtTokenService expiredIssuer = new JwtTokenService(
                TEST_SECRET,
                "buildgraph-api-test",
                Duration.ofSeconds(1),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        String token = expiredIssuer.issueAccessToken(user());
        JwtTokenService verifier = new JwtTokenService(
                TEST_SECRET,
                "buildgraph-api-test",
                Duration.ofMinutes(15),
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC)
        );

        assertThatThrownBy(() -> verifier.verifyAccessToken(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expired");
    }

    private Map<String, Object> user() {
        return Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Demo User",
                "role", "USER"
        );
    }
}

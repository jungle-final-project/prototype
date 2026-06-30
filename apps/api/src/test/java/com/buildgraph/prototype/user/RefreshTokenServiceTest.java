package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RefreshTokenServiceTest {
    private static final Instant NOW = Instant.parse("2026-06-29T09:00:00Z");
    private final RefreshTokenService refreshTokenService = new RefreshTokenService(
            new SecureRandom(),
            Duration.ofDays(30),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    @Test
    void issueReturnsOpaqueTokenAndHash() {
        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issue();

        assertThat(issued.token()).isNotBlank();
        assertThat(issued.tokenHash()).isNotBlank();
        assertThat(issued.tokenHash()).isNotEqualTo(issued.token());
    }

    @Test
    void hashIsStableForSameToken() {
        String token = refreshTokenService.issue().token();

        assertThat(refreshTokenService.hash(token)).isEqualTo(refreshTokenService.hash(token));
    }

    @Test
    void issueSetsExpiration() {
        RefreshTokenService.IssuedRefreshToken issued = refreshTokenService.issue();

        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(30)));
    }
}

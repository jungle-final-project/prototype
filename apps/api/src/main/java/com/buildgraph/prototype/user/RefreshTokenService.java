package com.buildgraph.prototype.user;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RefreshTokenService {
    private final SecureRandom secureRandom;
    private final Duration refreshTokenTtl;
    private final Clock clock;

    @Autowired
    public RefreshTokenService(
            @Value("${buildgraph.auth.refresh-token-ttl-days:30}") long refreshTokenTtlDays
    ) {
        this(new SecureRandom(), Duration.ofDays(refreshTokenTtlDays), Clock.systemUTC());
    }

    RefreshTokenService(SecureRandom secureRandom, Duration refreshTokenTtl, Clock clock) {
        this.secureRandom = secureRandom;
        this.refreshTokenTtl = refreshTokenTtl;
        this.clock = clock;
    }

    public IssuedRefreshToken issue() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        return new IssuedRefreshToken(token, hash(token), clock.instant().plus(refreshTokenTtl));
    }

    public String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    public record IssuedRefreshToken(String token, String tokenHash, Instant expiresAt) {
    }
}

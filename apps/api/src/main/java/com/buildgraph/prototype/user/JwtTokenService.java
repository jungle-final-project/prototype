package com.buildgraph.prototype.user;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtTokenService {
    private final byte[] secret;
    private final String issuer;
    private final Duration accessTokenTtl;
    private final Clock clock;

    @Autowired
    public JwtTokenService(
            @Value("${buildgraph.auth.jwt-secret:buildgraph-local-dev-jwt-secret-change-me-2026}") String jwtSecret,
            @Value("${buildgraph.auth.jwt-issuer:buildgraph-api}") String issuer,
            @Value("${buildgraph.auth.access-token-ttl-seconds:900}") long accessTokenTtlSeconds
    ) {
        this(jwtSecret, issuer, Duration.ofSeconds(accessTokenTtlSeconds), Clock.systemUTC());
    }

    JwtTokenService(String jwtSecret, String issuer, Duration accessTokenTtl, Clock clock) {
        this.secret = jwtSecret.getBytes(StandardCharsets.UTF_8);
        if (this.secret.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes.");
        }
        this.issuer = issuer;
        this.accessTokenTtl = accessTokenTtl;
        this.clock = clock;
    }

    public String issueAccessToken(Map<String, Object> user) {
        Instant issuedAt = clock.instant();
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(requiredString(user, "id"))
                .issueTime(Date.from(issuedAt))
                .expirationTime(Date.from(issuedAt.plus(accessTokenTtl)))
                .claim("email", requiredString(user, "email"))
                .claim("role", requiredString(user, "role"))
                .build();

        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.HS256)
                        .type(JOSEObjectType.JWT)
                        .build(),
                claims
        );
        try {
            jwt.sign(new MACSigner(secret));
            return jwt.serialize();
        } catch (JOSEException exception) {
            throw new IllegalStateException("Failed to issue access token.", exception);
        }
    }

    private String requiredString(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().isBlank()) {
            throw new IllegalArgumentException("JWT claim is missing: " + key);
        }
        return value.toString();
    }
}

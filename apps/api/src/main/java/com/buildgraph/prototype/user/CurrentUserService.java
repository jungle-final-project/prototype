package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {
    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenService jwtTokenService;
    private final boolean currentUserCacheEnabled;
    private final Cache<String, CurrentUser> usersByPublicId;

    @Autowired
    public CurrentUserService(JdbcTemplate jdbcTemplate, JwtTokenService jwtTokenService, Environment environment) {
        this(
                jdbcTemplate,
                jwtTokenService,
                currentUserCacheEnabled(environment),
                Duration.ofSeconds(longProperty(environment, "buildgraph.auth.current-user-cache.ttl-seconds", 120L)),
                longProperty(environment, "buildgraph.auth.current-user-cache.maximum-size", 5_000L)
        );
    }

    CurrentUserService(JdbcTemplate jdbcTemplate, JwtTokenService jwtTokenService) {
        this(jdbcTemplate, jwtTokenService, false, Duration.ofSeconds(120), 5_000L);
    }

    CurrentUserService(
            JdbcTemplate jdbcTemplate,
            JwtTokenService jwtTokenService,
            boolean currentUserCacheEnabled,
            Duration currentUserCacheTtl,
            long currentUserCacheMaximumSize
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtTokenService = jwtTokenService;
        this.currentUserCacheEnabled = currentUserCacheEnabled;
        this.usersByPublicId = Caffeine.newBuilder()
                .expireAfterWrite(currentUserCacheTtl)
                .maximumSize(Math.max(1L, currentUserCacheMaximumSize))
                .build();
    }

    public CurrentUser requireUser(String authorization) {
        String token = bearerToken(authorization);
        JWTClaimsSet claims = verifyJwt(token);
        return currentUserByPublicId(claims.getSubject());
    }

    public CurrentUser requireAdmin(String authorization) {
        CurrentUser user = requireUser(authorization);
        String freshRole = findRoleByInternalId(user.internalId());
        if (!"ADMIN".equals(freshRole)) {
            evictUser(user.id());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Admin permission is required.");
        }
        if ("ADMIN".equals(user.role())) {
            return user;
        }
        CurrentUser refreshedUser = findByPublicId(user.id());
        cacheUser(refreshedUser);
        return refreshedUser;
    }

    public void evictUser(String publicId) {
        if (publicId != null && !publicId.isBlank()) {
            usersByPublicId.invalidate(publicId);
        }
    }

    private CurrentUser currentUserByPublicId(String publicId) {
        if (!currentUserCacheEnabled) {
            return findByPublicId(publicId);
        }
        return usersByPublicId.get(publicId, this::findByPublicId);
    }

    private void cacheUser(CurrentUser user) {
        if (currentUserCacheEnabled && user != null && user.id() != null) {
            usersByPublicId.put(user.id(), user);
        }
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.");
        }
        return token;
    }

    private JWTClaimsSet verifyJwt(String token) {
        try {
            return jwtTokenService.verifyAccessToken(token);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login is required.", exception);
        }
    }

    private CurrentUser findByPublicId(String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               email,
                               name,
                               role,
                               created_at
                        FROM users
                        WHERE public_id = ?::uuid
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(this::currentUser)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
    }

    private String findRoleByInternalId(Long internalId) {
        return jdbcTemplate.queryForList("""
                        SELECT role
                        FROM users
                        WHERE id = ?
                          AND deleted_at IS NULL
                        """, internalId)
                .stream()
                .findFirst()
                .map(row -> DbValueMapper.string(row, "role"))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found."));
    }

    private CurrentUser currentUser(Map<String, Object> row) {
        return new CurrentUser(
                longValue(row, "internal_id"),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "email"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "role"),
                DbValueMapper.timestamp(row, "created_at")
        );
    }

    private static Long longValue(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static boolean currentUserCacheEnabled(Environment environment) {
        String explicit = environment.getProperty("buildgraph.auth.current-user-cache.enabled");
        if (explicit != null && !explicit.isBlank()) {
            return Boolean.parseBoolean(explicit);
        }
        return "caffeine".equalsIgnoreCase(environment.getProperty("spring.cache.type", "none"));
    }

    private static long longProperty(Environment environment, String propertyName, long fallback) {
        String value = environment.getProperty(propertyName);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    public record CurrentUser(
            Long internalId,
            String id,
            String email,
            String name,
            String role,
            Object createdAt
    ) {
        public Map<String, Object> toUserMap() {
            return MockData.map(
                    "id", id,
                    "email", email,
                    "name", name,
                    "role", role,
                    "createdAt", createdAt
            );
        }
    }
}

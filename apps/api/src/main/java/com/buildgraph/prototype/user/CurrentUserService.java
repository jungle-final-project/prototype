package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.nimbusds.jwt.JWTClaimsSet;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {
    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenService jwtTokenService;

    public CurrentUserService(JdbcTemplate jdbcTemplate, JwtTokenService jwtTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtTokenService = jwtTokenService;
    }

    public CurrentUser requireUser(String authorization) {
        String token = bearerToken(authorization);
        JWTClaimsSet claims = verifyJwt(token);
        return findByPublicId(claims.getSubject());
    }

    public CurrentUser requireAdmin(String authorization) {
        CurrentUser user = requireUser(authorization);
        if (!"ADMIN".equals(user.role())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다.");
        }
        return user;
    }

    private String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        String token = authorization.substring("Bearer ".length()).trim();
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        return token;
    }

    private JWTClaimsSet verifyJwt(String token) {
        try {
            return jwtTokenService.verifyAccessToken(token);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.", exception);
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
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "등록된 사용자를 찾을 수 없습니다."));
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

package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.nimbusds.jwt.JWTClaimsSet;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CurrentUserService {
    private final JdbcTemplate jdbcTemplate;
    private final JwtTokenService jwtTokenService;
    // JWT subject → CurrentUser 단기 캐시. 모든 인증 요청(트래픽 대다수)이 JWT 검증 뒤 users SELECT를
    // 반복하던 것을 제거한다 — parts/home 응답 캐시가 히트해도 이 조회만은 남아 DB를 치던 마지막 조각.
    // role 변경·소프트 삭제가 최대 TTL만큼 늦게 반영되는 창이 생기므로 TTL은 짧게 유지하고,
    // 프로필 변경 경로는 evictCachedUser()로 즉시 무효화한다.
    private final ReadThroughTtlCache<String, CurrentUser> userCache;

    @Autowired
    public CurrentUserService(
            JdbcTemplate jdbcTemplate,
            JwtTokenService jwtTokenService,
            @Value("${buildgraph.auth.user-cache.ttl-seconds:30}") long userCacheTtlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.jwtTokenService = jwtTokenService;
        this.userCache = new ReadThroughTtlCache<>(Duration.ofSeconds(userCacheTtlSeconds), 4096);
    }

    // 편의 생성자(테스트용): 캐시 꺼짐 — 기존 단건 조회 동작 그대로.
    public CurrentUserService(JdbcTemplate jdbcTemplate, JwtTokenService jwtTokenService) {
        this(jdbcTemplate, jwtTokenService, 0L);
    }

    public CurrentUser requireUser(String authorization) {
        String token = bearerToken(authorization);
        JWTClaimsSet claims = verifyJwt(token);
        // 미존재/삭제 사용자는 loader가 401을 던져 캐시에 남지 않는다(부정 캐시 없음).
        return userCache.get(claims.getSubject(), () -> findByPublicId(claims.getSubject()));
    }

    /** 사용자 표시 정보·권한을 바꾸는 mutation 직후 호출 — TTL을 기다리지 않고 즉시 반영한다. */
    public void evictCachedUser(String publicId) {
        if (publicId != null) {
            userCache.remove(publicId);
        }
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

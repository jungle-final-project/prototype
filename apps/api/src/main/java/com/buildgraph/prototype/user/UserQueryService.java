package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserQueryService {
    private final JdbcTemplate jdbcTemplate;
    private final PasswordService passwordService;
    private final JwtTokenService jwtTokenService;

    public UserQueryService(JdbcTemplate jdbcTemplate, PasswordService passwordService, JwtTokenService jwtTokenService) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordService = passwordService;
        this.jwtTokenService = jwtTokenService;
    }

    public Map<String, Object> login(String email, String password) {
        Map<String, Object> user = findByEmail(email);
        String passwordHash = DbValueMapper.string(user, "password_hash");
        if (!passwordService.matches(password, passwordHash)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        String role = DbValueMapper.string(user, "role");
        Map<String, Object> userDto = userMap(user);
        return MockData.map(
                "accessToken", jwtTokenService.issueAccessToken(userDto),
                "refreshToken", "demo-refresh-" + role.toLowerCase(),
                "user", userDto
        );
    }

    public Map<String, Object> signup(
            String name,
            String email,
            String password,
            Boolean termsAccepted,
            Boolean marketingAccepted
    ) {
        if (!Boolean.TRUE.equals(termsAccepted)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "약관 동의가 필요합니다.");
        }
        List<Map<String, Object>> existing = findRowsByEmail(email);
        if (!existing.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_RESOURCE", "이미 가입된 이메일입니다.");
        }
        String passwordHash = passwordService.hash(password);
        Map<String, Object> row = jdbcTemplate.queryForMap("""
                INSERT INTO users (email, password_hash, name, role, terms_accepted_at, marketing_accepted_at)
                VALUES (?, ?, ?, 'USER', now(), CASE WHEN ? THEN now() ELSE NULL END)
                RETURNING public_id::text AS id, email, name, role, created_at
                """, email, passwordHash, name, Boolean.TRUE.equals(marketingAccepted));
        return userMap(row);
    }

    public Map<String, Object> me(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer demo-access-")) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
        }
        String email = authorization.contains("admin") ? "admin@example.com" : "user@example.com";
        return userMap(findByEmail(email));
    }

    private Map<String, Object> findByEmail(String email) {
        return findRowsByEmail(email)
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "등록된 사용자를 찾을 수 없습니다."));
    }

    private List<Map<String, Object>> findRowsByEmail(String email) {
        return jdbcTemplate.queryForList("""
                SELECT public_id::text AS id, email, password_hash, name, role, created_at
                FROM users
                WHERE email = ?
                  AND deleted_at IS NULL
                """, email);
    }

    private Map<String, Object> userMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "email", DbValueMapper.string(row, "email"),
                "name", DbValueMapper.string(row, "name"),
                "role", DbValueMapper.string(row, "role"),
                "createdAt", DbValueMapper.timestamp(row, "created_at")
        );
    }
}

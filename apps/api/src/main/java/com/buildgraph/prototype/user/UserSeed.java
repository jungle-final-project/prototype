package com.buildgraph.prototype.user;

import com.buildgraph.prototype.common.MockData;
import java.util.Map;

public final class UserSeed {
    private UserSeed() {
    }

    public static Map<String, Object> login(String email) {
        String role = email.startsWith("admin") ? "ADMIN" : "USER";
        return MockData.map(
                "accessToken", "demo-access-" + role.toLowerCase(),
                "refreshToken", "demo-refresh-" + role.toLowerCase(),
                "user", MockData.map(
                        "id", role.equals("ADMIN") ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000001004",
                        "email", email,
                        "name", role.equals("ADMIN") ? "관리자" : "홍길동",
                        "role", role
                )
        );
    }

    public static Map<String, Object> signup(String name, String email) {
        return MockData.map("id", "00000000-0000-4000-8000-000000001004", "email", email, "name", name, "role", "USER", "createdAt", MockData.now());
    }

    public static Map<String, Object> me(String authorization) {
        boolean admin = authorization != null && authorization.contains("admin");
        return MockData.map(
                "id", admin ? "00000000-0000-4000-8000-000000000001" : "00000000-0000-4000-8000-000000001004",
                "email", admin ? "admin@example.com" : "user@example.com",
                "name", admin ? "관리자" : "홍길동",
                "role", admin ? "ADMIN" : "USER"
        );
    }
}

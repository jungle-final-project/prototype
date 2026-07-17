package com.buildgraph.prototype.user;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class PasswordService {
    private final PasswordEncoder passwordEncoder;

    // bcrypt cost(2^n 라운드) 노브. 기본 10 = 기존 new BCryptPasswordEncoder()와 동일(프로덕션 불변).
    // 주의: 검증(matches) 비용은 저장된 해시에 박힌 cost를 따른다 — 노브를 낮춰도 기존 계정 로그인은
    // 이전 cost로 검증되므로, 부하테스트 계정은 낮춘 cost로 재해시(비밀번호 재설정)해야 효과가 난다.
    @Autowired
    public PasswordService(@Value("${buildgraph.auth.bcrypt-strength:10}") int bcryptStrength) {
        this.passwordEncoder = new BCryptPasswordEncoder(bcryptStrength);
    }

    // 테스트 편의(기존 new PasswordService() 호출처 유지) — 기본 cost 10 그대로.
    PasswordService() {
        this(10);
    }

    public String hash(String rawPassword) {
        return passwordEncoder.encode(rawPassword);
    }

    public boolean matches(String rawPassword, String passwordHash) {
        return rawPassword != null && passwordHash != null && passwordEncoder.matches(rawPassword, passwordHash);
    }
}

package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceTest {
    // 4 = bcrypt 최소 cost — 해시/검증 계약은 cost와 무관하므로 테스트는 가장 싼 값으로 빠르게 돈다.
    private final PasswordService passwordService = new PasswordService(4);

    @Test
    void hashDoesNotStoreRawPassword() {
        String hash = passwordService.hash("passw0rd!");

        assertThat(hash).isNotEqualTo("passw0rd!");
    }

    @Test
    void matchesOriginalPassword() {
        String hash = passwordService.hash("passw0rd!");

        assertThat(passwordService.matches("passw0rd!", hash)).isTrue();
    }

    @Test
    void rejectsWrongPassword() {
        String hash = passwordService.hash("passw0rd!");

        assertThat(passwordService.matches("wrong-password", hash)).isFalse();
    }

    @Test
    void hashUsesRandomSalt() {
        String firstHash = passwordService.hash("passw0rd!");
        String secondHash = passwordService.hash("passw0rd!");

        assertThat(firstHash).isNotEqualTo(secondHash);
        assertThat(passwordService.matches("passw0rd!", firstHash)).isTrue();
        assertThat(passwordService.matches("passw0rd!", secondHash)).isTrue();
    }
}

package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordServiceTest {
    private final PasswordService passwordService = new PasswordService();

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

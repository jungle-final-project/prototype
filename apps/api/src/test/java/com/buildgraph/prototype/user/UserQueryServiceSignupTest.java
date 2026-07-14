package com.buildgraph.prototype.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.common.ApiException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class UserQueryServiceSignupTest {
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final PasswordService passwordService = new PasswordService();
    private final CurrentUserService currentUserService = org.mockito.Mockito.mock(CurrentUserService.class);
    private final GoogleOAuthRuntimeStore googleOAuthRuntimeStore = org.mockito.Mockito.mock(GoogleOAuthRuntimeStore.class);
    private final JwtTokenService jwtTokenService = new JwtTokenService(
            "test-buildgraph-jwt-secret-change-me-2026",
            "buildgraph-api-test",
            java.time.Duration.ofMinutes(15),
            java.time.Clock.systemUTC()
    );
    private final RefreshTokenService refreshTokenService = new RefreshTokenService(
            new java.security.SecureRandom(),
            java.time.Duration.ofDays(30),
            java.time.Clock.systemUTC()
    );
    private final UserQueryService userQueryService = new UserQueryService(
            jdbcTemplate,
            passwordService,
            jwtTokenService,
            currentUserService,
            refreshTokenService,
            googleOAuthRuntimeStore
    );

    @Test
    void signupStoresPasswordHash() {
        AtomicReference<String> storedPasswordHash = new AtomicReference<>();
        AtomicReference<String> storedPhoneNumber = new AtomicReference<>();
        AtomicReference<String> storedPostalCode = new AtomicReference<>();
        AtomicReference<String> storedAddressLine1 = new AtomicReference<>();
        AtomicReference<String> storedAddressLine2 = new AtomicReference<>();
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of());
        when(jdbcTemplate.queryForMap(
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                org.mockito.ArgumentMatchers.anyBoolean()
        ))
                .thenAnswer(invocation -> {
                    storedPasswordHash.set(invocation.getArgument(2));
                    storedPhoneNumber.set(invocation.getArgument(4));
                    storedPostalCode.set(invocation.getArgument(5));
                    storedAddressLine1.set(invocation.getArgument(6));
                    storedAddressLine2.set(invocation.getArgument(7));
                    return userRow();
                });

        Map<String, Object> user = userQueryService.signup(
                "홍길동",
                "user@example.com",
                "passw0rd!",
                "01012345678",
                " 06236 ",
                "서울시   강남구   테헤란로 1",
                " 101호 ",
                true,
                false
        );

        assertThat(user).containsEntry("email", "user@example.com");
        assertThat(storedPasswordHash.get()).isNotEqualTo("passw0rd!");
        assertThat(passwordService.matches("passw0rd!", storedPasswordHash.get())).isTrue();
        assertThat(storedPhoneNumber.get()).isEqualTo("010-1234-5678");
        assertThat(storedPostalCode.get()).isEqualTo("06236");
        assertThat(storedAddressLine1.get()).isEqualTo("서울시 강남구 테헤란로 1");
        assertThat(storedAddressLine2.get()).isEqualTo("101호");
    }

    @Test
    void signupRejectsDuplicateEmail() {
        when(jdbcTemplate.queryForList(anyString(), anyString())).thenReturn(List.of(userRow()));

        assertThatThrownBy(() -> userQueryService.signup(
                "홍길동",
                "user@example.com",
                "passw0rd!",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호",
                true,
                false
        ))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
                    assertThat(exception.code()).isEqualTo("DUPLICATE_RESOURCE");
                });
        verify(jdbcTemplate).queryForList(anyString(), anyString());
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void signupRequiresTermsAccepted() {
        assertThatThrownBy(() -> userQueryService.signup(
                "홍길동",
                "user@example.com",
                "passw0rd!",
                "010-1234-5678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호",
                false,
                false
        ))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void signupRejectsInvalidContactBeforeCheckingDuplicateEmail() {
        assertThatThrownBy(() -> userQueryService.signup(
                "홍길동",
                "user@example.com",
                "passw0rd!",
                "abc",
                "123",
                "test",
                "101호",
                true,
                false
        ))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("field", "phoneNumber");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_FORMAT");
                    assertThat(exception.details()).containsEntry("message", "전화번호는 숫자와 하이픈만 입력해 주세요.");
                });
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void updateMeStoresNormalizedProfileAndReturnsUser() {
        when(currentUserService.requireUser("Bearer jwt-user-token")).thenReturn(new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Before User",
                "USER",
                null
        ));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(userRowWithContact()));

        Map<String, Object> user = userQueryService.updateMe(
                "Bearer jwt-user-token",
                "passw0rd!",
                null,
                " 홍길동 ",
                "01012345678",
                " 06236 ",
                "서울시   강남구   테헤란로 1",
                " 101호 "
        );

        assertThat(user).containsEntry("name", "홍길동");
        assertThat(user).containsEntry("phoneNumber", "010-1234-5678");
        assertThat(user).containsEntry("postalCode", "06236");
        assertThat(user).containsEntry("addressLine1", "서울시 강남구 테헤란로 1");
        assertThat(user).containsEntry("addressLine2", "101호");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("UPDATE users"),
                eq("홍길동"),
                eq("010-1234-5678"),
                eq("06236"),
                eq("서울시 강남구 테헤란로 1"),
                eq("101호"),
                eq(1004L)
        );
    }

    @Test
    void updateMeRejectsInvalidContactBeforeUpdatingProfile() {
        when(currentUserService.requireUser("Bearer jwt-user-token")).thenReturn(new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Before User",
                "USER",
                null
        ));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(userRowWithContact()));

        assertThatThrownBy(() -> userQueryService.updateMe(
                "Bearer jwt-user-token",
                "passw0rd!",
                null,
                "홍길동",
                "12345",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호"
        ))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("field", "phoneNumber");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_FORMAT");
                    assertThat(exception.details()).containsEntry("message", "전화번호는 지역번호를 포함해 10~11자리 숫자로 입력해 주세요.");
                });

        assertThatThrownBy(() -> userQueryService.updateMe(
                "Bearer jwt-user-token",
                "passw0rd!",
                null,
                "홍길동",
                "01012345678",
                "1234",
                "서울시 강남구 테헤란로 1",
                "101호"
        ))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.status()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.code()).isEqualTo("VALIDATION_ERROR");
                    assertThat(exception.details()).containsEntry("field", "postalCode");
                    assertThat(exception.details()).containsEntry("reason", "INVALID_FORMAT");
                    assertThat(exception.details()).containsEntry("message", "우편번호는 5자리 숫자로 입력해 주세요.");
                });

        verify(jdbcTemplate, never()).update(
                org.mockito.ArgumentMatchers.contains("UPDATE users"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any()
        );
    }

    @Test
    void updateMeAcceptsGoogleProfileVerificationToken() {
        when(currentUserService.requireUser("Bearer jwt-google-token")).thenReturn(new CurrentUserService.CurrentUser(
                1004L,
                "00000000-0000-4000-8000-000000001004",
                "user@example.com",
                "Google User",
                "USER",
                null
        ));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L)))
                .thenReturn(List.of(googleUserRowWithContact()));
        when(googleOAuthRuntimeStore.getProfileVerificationToken("google-profile-token"))
                .thenReturn(new GoogleProfileVerification("00000000-0000-4000-8000-000000001004", "google-sub-1"));
        when(jdbcTemplate.queryForList(anyString(), eq(1004L), eq("google-sub-1")))
                .thenReturn(List.of(Map.of("id", 7001L)));

        Map<String, Object> user = userQueryService.updateMe(
                "Bearer jwt-google-token",
                null,
                "google-profile-token",
                "Google User",
                "01012345678",
                "06236",
                "서울시 강남구 테헤란로 1",
                "101호"
        );

        assertThat(user).containsEntry("name", "Google User");
        verify(googleOAuthRuntimeStore).consumeProfileVerificationToken("google-profile-token");
        verify(jdbcTemplate).update(
                org.mockito.ArgumentMatchers.contains("UPDATE users"),
                eq("Google User"),
                eq("010-1234-5678"),
                eq("06236"),
                eq("서울시 강남구 테헤란로 1"),
                eq("101호"),
                eq(1004L)
        );
    }

    private Map<String, Object> userRow() {
        return Map.of(
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "홍길동",
                "role", "USER"
        );
    }

    private Map<String, Object> userRowWithContact() {
        return Map.of(
                "internal_id", 1004L,
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "password_hash", passwordService.hash("passw0rd!"),
                "name", "홍길동",
                "role", "USER",
                "phone_number", "010-1234-5678",
                "postal_code", "06236",
                "address_line1", "서울시 강남구 테헤란로 1",
                "address_line2", "101호"
        );
    }

    private Map<String, Object> googleUserRowWithContact() {
        return Map.of(
                "internal_id", 1004L,
                "id", "00000000-0000-4000-8000-000000001004",
                "email", "user@example.com",
                "name", "Google User",
                "role", "USER",
                "phone_number", "010-1234-5678",
                "postal_code", "06236",
                "address_line1", "서울시 강남구 테헤란로 1",
                "address_line2", "101호"
        );
    }
}

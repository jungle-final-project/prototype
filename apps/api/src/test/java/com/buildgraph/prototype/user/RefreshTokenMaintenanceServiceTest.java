package com.buildgraph.prototype.user;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RefreshTokenMaintenanceServiceTest {

    @Test
    void cleanupRefreshTokensRevokesExpiredAndExcessTokens() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RefreshTokenMaintenanceService service = new RefreshTokenMaintenanceService(jdbcTemplate, true, true, 10);
        when(jdbcTemplate.update(anyString())).thenReturn(2);
        when(jdbcTemplate.update(anyString(), eq(10))).thenReturn(3);

        service.cleanupRefreshTokens();

        verify(jdbcTemplate).update(anyString());
        verify(jdbcTemplate).update(anyString(), eq(10));
    }

    @Test
    void cleanupRefreshTokensCanBeDisabled() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RefreshTokenMaintenanceService service = new RefreshTokenMaintenanceService(jdbcTemplate, false, true, 10);

        service.cleanupRefreshTokens();

        verify(jdbcTemplate, never()).update(anyString());
        verify(jdbcTemplate, never()).update(anyString(), eq(10));
    }

    @Test
    void startupCleanupUsesSameCleanupPolicy() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RefreshTokenMaintenanceService service = new RefreshTokenMaintenanceService(jdbcTemplate, true, true, 10);
        when(jdbcTemplate.update(anyString())).thenReturn(2);
        when(jdbcTemplate.update(anyString(), eq(10))).thenReturn(3);

        service.cleanupRefreshTokensOnStartup();

        verify(jdbcTemplate).update(anyString());
        verify(jdbcTemplate).update(anyString(), eq(10));
    }

    @Test
    void startupCleanupCanBeDisabledSeparately() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RefreshTokenMaintenanceService service = new RefreshTokenMaintenanceService(jdbcTemplate, true, false, 10);

        service.cleanupRefreshTokensOnStartup();

        verify(jdbcTemplate, never()).update(anyString());
        verify(jdbcTemplate, never()).update(anyString(), eq(10));
    }
}

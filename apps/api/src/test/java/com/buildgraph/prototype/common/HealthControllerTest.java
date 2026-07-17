package com.buildgraph.prototype.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// MockMvc 테스트는 캐시를 끄고(ttl=0) 요청↔프로브 1:1 계약을 검증한다. 캐시 동작 자체는 아래
// 직접 인스턴스 테스트가 검증한다(WebMvcTest 컨텍스트는 테스트 간 공유라 캐시가 새면 간섭됨).
@WebMvcTest(HealthController.class)
@TestPropertySource(properties = "buildgraph.health.db-probe-cache-ttl-ms=0")
class HealthControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JdbcTemplate jdbcTemplate;

    @Test
    void healthReturnsUpWhenDatabaseProbeSucceeds() throws Exception {
        when(jdbcTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.database").value("UP"));

        verify(jdbcTemplate).queryForObject("select 1", Integer.class);
    }

    @Test
    void healthReturnsServiceUnavailableWhenDatabaseProbeFails() throws Exception {
        when(jdbcTemplate.queryForObject("select 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));

        mockMvc.perform(get("/api/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.database").doesNotExist());

        verify(jdbcTemplate).queryForObject("select 1", Integer.class);
    }

    @Test
    void healthReusesCachedDatabaseProbeWithinTtl() {
        JdbcTemplate directTemplate = mock(JdbcTemplate.class);
        when(directTemplate.queryForObject("select 1", Integer.class)).thenReturn(1);
        HealthController controller = new HealthController(directTemplate, 60_000L);

        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);

        // TTL 내 두 번째 호출은 DB 커넥션을 쓰지 않는다 — 풀 포화 시 헬스가 먼저 죽는 것을 막는 핵심.
        verify(directTemplate, times(1)).queryForObject("select 1", Integer.class);
    }

    @Test
    void healthDoesNotCacheDownSoRecoveryIsDetectedImmediately() {
        JdbcTemplate directTemplate = mock(JdbcTemplate.class);
        when(directTemplate.queryForObject("select 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"))
                .thenReturn(1);
        HealthController controller = new HealthController(directTemplate, 60_000L);

        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
        // DOWN은 캐시하지 않으므로 다음 호출이 즉시 재탐지해 복구를 반영한다.
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);

        verify(directTemplate, times(2)).queryForObject("select 1", Integer.class);
    }
}

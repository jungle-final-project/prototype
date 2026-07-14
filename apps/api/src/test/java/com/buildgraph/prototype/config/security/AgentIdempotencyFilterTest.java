package com.buildgraph.prototype.config.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AgentIdempotencyFilterTest {
    private final AgentIdempotencyService service = mock(AgentIdempotencyService.class);
    private final AgentIdempotencyFilter filter = new AgentIdempotencyFilter(
            new AgentIdempotencyKeyExtractor(),
            service,
            new SecurityErrorResponseWriter(new ObjectMapper())
    );

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void serverFailureAbandonsReservationInsteadOfCachingTheFailure() throws Exception {
        assertFailureAbandonsReservation(503);
    }

    @Test
    void transientAsConflictAbandonsReservationSoTheSameDiagnosisCanRetry() throws Exception {
        assertFailureAbandonsReservation(409);
    }

    private void assertFailureAbandonsReservation(int status) throws Exception {
        AgentPrincipal principal = new AgentPrincipal(10L, "device-a", 20L, "ACTIVE");
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())
        );
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/agent/as-requests");
        request.addHeader("Idempotency-Key", "diagnosis-1");
        request.setContentType("application/json");
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(service.reserve(eq(principal), eq("POST"), eq("/api/agent/as-requests"), eq("diagnosis-1"), anyString()))
                .thenReturn(AgentIdempotencyDecision.proceed(100L));

        filter.doFilter(request, response, (ignoredRequest, wrappedResponse) -> {
            HttpServletResponse httpResponse = (HttpServletResponse) wrappedResponse;
            httpResponse.setStatus(status);
            httpResponse.getWriter().write("unavailable");
        });

        assertThat(response.getStatus()).isEqualTo(status);
        assertThat(response.getContentAsString()).isEqualTo("unavailable");
        verify(service).abandon(100L);
        verify(service, never()).complete(eq(100L), eq(status), anyString(), anyString());
    }
}

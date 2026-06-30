package com.buildgraph.prototype.admin;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.agent.AgentQueryService;
import com.buildgraph.prototype.price.PriceQueryService;
import com.buildgraph.prototype.rag.RagQueryService;
import com.buildgraph.prototype.ticket.TicketQueryService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(AdminController.class)
class AdminControllerTest {
    private static final String ADMIN_TOKEN = "Bearer jwt-admin-token";
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AdminQueryService adminQueryService;

    @MockitoBean
    private AgentQueryService agentQueryService;

    @MockitoBean
    private RagQueryService ragQueryService;

    @MockitoBean
    private TicketQueryService ticketQueryService;

    @MockitoBean
    private PriceQueryService priceQueryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireAdmin(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireAdmin(USER_TOKEN))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "관리자 권한이 필요합니다."));
    }

    @Test
    void dashboardReturnsUnauthorizedErrorResponseWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void dashboardReturnsForbiddenErrorResponseWhenTokenIsNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void dashboardReturnsAdminDashboardDtoForAdminToken() throws Exception {
        when(adminQueryService.dashboard()).thenReturn(Map.of(
                "agentRunning", 1,
                "openTickets", 3,
                "priceJobsRunning", 0,
                "degraded", false,
                "generatedAt", "2026-06-29T10:50:00Z"
        ));

        mockMvc.perform(get("/api/admin/dashboard")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRunning").value(1))
                .andExpect(jsonPath("$.openTickets").value(3))
                .andExpect(jsonPath("$.priceJobsRunning").value(0))
                .andExpect(jsonPath("$.degraded").value(false))
                .andExpect(jsonPath("$.generatedAt").value("2026-06-29T10:50:00Z"));

        verify(adminQueryService).dashboard();
    }

    @Test
    void auditLogsReturnsUnauthorizedErrorResponseWhenAdminTokenIsMissing() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs/recent"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void auditLogsReturnsForbiddenErrorResponseWhenTokenIsNotAdmin() throws Exception {
        mockMvc.perform(get("/api/admin/audit-logs/recent")
                        .header("Authorization", USER_TOKEN))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("관리자 권한이 필요합니다."));

        verifyNoInteractions(adminQueryService);
    }

    @Test
    void auditLogsReturnsRecentItemsForAdminToken() throws Exception {
        when(adminQueryService.auditLogs()).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "action", "AS_TICKET_UPDATED",
                        "targetType", "as_tickets",
                        "targetId", "4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a",
                        "metadata", Map.of(
                                "beforeStatus", "OPEN",
                                "afterStatus", "IN_PROGRESS"
                        ),
                        "createdAt", "2026-06-29T10:45:00Z"
                ))
        ));

        mockMvc.perform(get("/api/admin/audit-logs/recent")
                        .header("Authorization", ADMIN_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].action").value("AS_TICKET_UPDATED"))
                .andExpect(jsonPath("$.items[0].targetType").value("as_tickets"))
                .andExpect(jsonPath("$.items[0].targetId").value("4aef8ef7-1dc7-45d1-bfc2-bb0cfdaf7f8a"))
                .andExpect(jsonPath("$.items[0].metadata.beforeStatus").value("OPEN"))
                .andExpect(jsonPath("$.items[0].metadata.afterStatus").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.items[0].createdAt").value("2026-06-29T10:45:00Z"));

        verify(adminQueryService).auditLogs();
    }
}

package com.buildgraph.prototype.part;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(PartController.class)
class PartControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PartQueryService partQueryService;

    @MockitoBean
    private ToolCheckService toolCheckService;

    @MockitoBean
    private NaverShoppingOfferService naverShoppingOfferService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
    }

    @Test
    void toolCheckReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        mockMvc.perform(post("/api/tools/power/check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verifyNoInteractions(toolCheckService);
    }

    @Test
    void toolCheckRunsForAuthenticatedUserToken() throws Exception {
        when(toolCheckService.checkTool(eq("power"), anyMap())).thenReturn(Map.of(
                "tool", "power",
                "status", "PASS",
                "confidence", "HIGH",
                "summary", "전력 검증 통과",
                "details", Map.of("ratedHeadroomW", 180)
        ));

        mockMvc.perform(post("/api/tools/power/check")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tool").value("power"))
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.confidence").value("HIGH"));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(toolCheckService).checkTool(eq("power"), anyMap());
    }
}

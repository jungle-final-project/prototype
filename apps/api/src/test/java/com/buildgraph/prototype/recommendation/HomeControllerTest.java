package com.buildgraph.prototype.recommendation;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

@WebMvcTest(HomeController.class)
class HomeControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000001001",
            "user@example.com",
            "Demo User",
            "USER",
            null
    );

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthenticatedHomeService authenticatedHomeService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @BeforeEach
    void setUpAuth() {
        when(currentUserService.requireUser(null))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));
        when(currentUserService.requireUser(USER_TOKEN)).thenReturn(USER);
    }

    @Test
    void authenticatedHomeRequiresLogin() throws Exception {
        mockMvc.perform(get("/api/home"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(authenticatedHomeService);
    }

    @Test
    void userCanLoadAggregatedHome() throws Exception {
        when(authenticatedHomeService.home(USER)).thenReturn(Map.of(
                "categoryParts", Map.of("GPU", List.of(Map.of(
                        "id", "gpu-public-id",
                        "category", "GPU",
                        "name", "RTX 5070",
                        "price", 850000
                ))),
                "recommendedParts", Map.of(
                        "items", List.of(Map.of(
                                "recommendationId", "home-part-gpu-public-id",
                                "rankPosition", 0,
                                "part", Map.of(
                                        "id", "gpu-public-id",
                                        "category", "GPU",
                                        "name", "RTX 5070",
                                        "price", 850000
                                )
                        )),
                        "generatedAt", "2026-07-14T00:00:00Z",
                        "fallbackUsed", true
                )
        ));

        mockMvc.perform(get("/api/home").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryParts.GPU[0].name").value("RTX 5070"))
                .andExpect(jsonPath("$.recommendedParts.items[0].recommendationId").value("home-part-gpu-public-id"));

        verify(authenticatedHomeService).home(USER);
    }
}

package com.buildgraph.prototype.quote;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(QuoteDraftController.class)
class QuoteDraftControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteDraftQueryService quoteDraftQueryService;

    @MockitoBean
    private QuoteDraftHistoryService quoteDraftHistoryService;

    @Test
    void applyAiBuildReturnsUnauthorizedWhenTokenIsMissing() throws Exception {
        when(quoteDraftQueryService.applyAiBuild(eq(null), anyMap()))
                .thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다."));

        mockMvc.perform(put("/api/quote-drafts/current/apply-ai-build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "conflictPolicy": "REPLACE",
                                  "items": [
                                    {
                                      "partId": "00000000-0000-4000-8000-000000000101",
                                      "category": "CPU",
                                      "quantity": 1
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("로그인이 필요합니다."));

        verify(quoteDraftQueryService).applyAiBuild(eq(null), anyMap());
    }

    @Test
    void applyAiBuildReturnsUpdatedQuoteDraft() throws Exception {
        when(quoteDraftQueryService.applyAiBuild(eq(USER_TOKEN), anyMap())).thenReturn(Map.of(
                "id", "draft-ai-apply",
                "status", "ACTIVE",
                "name", "셀프 견적",
                "items", List.of(Map.of(
                        "id", "draft-item-1",
                        "partId", "00000000-0000-4000-8000-000000000101",
                        "category", "CPU",
                        "name", "Ryzen 7",
                        "manufacturer", "AMD",
                        "quantity", 1,
                        "unitPriceAtAdd", 420000,
                        "currentPrice", 420000,
                        "lineTotal", 420000
                )),
                "totalPrice", 420000,
                "itemCount", 1
        ));

        mockMvc.perform(put("/api/quote-drafts/current/apply-ai-build")
                        .header("Authorization", USER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "buildId": "ai-budget-2000000-balanced",
                                  "conflictPolicy": "REPLACE",
                                  "items": [
                                    {
                                      "partId": "00000000-0000-4000-8000-000000000101",
                                      "category": "CPU",
                                      "quantity": 1
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].partId").value("00000000-0000-4000-8000-000000000101"))
                .andExpect(jsonPath("$.items[0].category").value("CPU"))
                .andExpect(jsonPath("$.totalPrice").value(420000));

        verify(quoteDraftQueryService).applyAiBuild(eq(USER_TOKEN), anyMap());
    }

    @Test
    void listsComparesAndRestoresOwnedDraftHistory() throws Exception {
        String historyId = "11111111-1111-4111-8111-111111111111";
        when(quoteDraftHistoryService.list(USER_TOKEN)).thenReturn(Map.of(
                "items", List.of(Map.of("id", historyId, "actionLabel", "GPU 교체 전")),
                "retentionDays", 30,
                "maxItems", 20
        ));
        when(quoteDraftHistoryService.comparison(USER_TOKEN, historyId, "pubg", "qhd"))
                .thenReturn(Map.of("restorable", true, "differences", List.of(Map.of("category", "GPU"))));
        when(quoteDraftHistoryService.restore(eq(USER_TOKEN), eq(historyId), anyMap(), eq("22222222-2222-4222-8222-222222222222")))
                .thenReturn(Map.of("status", "ACTIVE", "items", List.of(), "totalPrice", 0, "itemCount", 0));

        mockMvc.perform(get("/api/quote-drafts/current/history").header("Authorization", USER_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].actionLabel").value("GPU 교체 전"));
        mockMvc.perform(get("/api/quote-drafts/current/history/{historyId}/comparison", historyId)
                        .header("Authorization", USER_TOKEN)
                        .queryParam("game", "pubg")
                        .queryParam("resolution", "qhd"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.restorable").value(true));
        mockMvc.perform(post("/api/quote-drafts/current/history/{historyId}/restore", historyId)
                        .header("Authorization", USER_TOKEN)
                        .header("X-Quote-Draft-Change-Group", "22222222-2222-4222-8222-222222222222")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"confirmCompatibilityRisk\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }
}

package com.buildgraph.prototype.quote;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
}

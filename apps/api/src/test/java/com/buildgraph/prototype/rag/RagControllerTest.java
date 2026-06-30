package com.buildgraph.prototype.rag;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(RagController.class)
class RagControllerTest {
    private static final String USER_TOKEN = "Bearer jwt-user-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RagQueryService ragQueryService;

    @MockitoBean
    private CurrentUserService currentUserService;

    @Test
    void searchPassesQueryAndPaginationToService() throws Exception {
        when(ragQueryService.search("gpu", "BUILD_RECOMMEND", "PART_SPEC", 1, 5)).thenReturn(Map.of(
                "items", List.of(Map.of(
                        "id", "00000000-0000-4000-8000-000000014002",
                        "sourceId", "part-catalog-rtx50-tool-ready-dimensions",
                        "summary", "RTX 50 GPU catalog evidence",
                        "score", "0.93000",
                        "metadata", Map.of("sourceType", "PART_SPEC")
                )),
                "page", 1,
                "size", 5,
                "total", 1
        ));

        mockMvc.perform(get("/api/rag/search")
                        .header("Authorization", USER_TOKEN)
                        .param("q", "gpu")
                        .param("purpose", "BUILD_RECOMMEND")
                        .param("sourceType", "PART_SPEC")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value("00000000-0000-4000-8000-000000014002"))
                .andExpect(jsonPath("$.items[0].sourceId").value("part-catalog-rtx50-tool-ready-dimensions"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(5))
                .andExpect(jsonPath("$.total").value(1));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(ragQueryService).search("gpu", "BUILD_RECOMMEND", "PART_SPEC", 1, 5);
    }

    @Test
    void searchReturnsBadRequestWhenSizeIsTooLarge() throws Exception {
        when(ragQueryService.search("gpu", null, null, 0, 101))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "size는 1 이상 100 이하이어야 합니다."
                ));

        mockMvc.perform(get("/api/rag/search")
                        .header("Authorization", USER_TOKEN)
                        .param("q", "gpu")
                        .param("page", "0")
                        .param("size", "101"))
                .andExpect(status().isBadRequest());

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(ragQueryService).search("gpu", null, null, 0, 101);
    }

    @Test
    void searchUsesLimitAsSizeAliasWhenSizeIsMissing() throws Exception {
        when(ragQueryService.search("5090", "REQUIREMENT_PARSE", null, null, 3)).thenReturn(Map.of(
                "items", List.of(),
                "page", 0,
                "size", 3,
                "total", 0
        ));

        mockMvc.perform(get("/api/rag/search")
                        .header("Authorization", USER_TOKEN)
                        .param("q", "5090")
                        .param("purpose", "REQUIREMENT_PARSE")
                        .param("limit", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(3));

        verify(currentUserService).requireUser(USER_TOKEN);
        verify(ragQueryService).search("5090", "REQUIREMENT_PARSE", null, null, 3);
    }
}

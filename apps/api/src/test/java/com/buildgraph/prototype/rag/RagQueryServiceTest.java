package com.buildgraph.prototype.rag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RagQueryServiceTest {

    @Test
    void fallsBackToKeywordSearchWhenVectorCorpusIsEmpty() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        RagEmbeddingService embeddingService = mock(RagEmbeddingService.class);
        RagVectorPolicy vectorPolicy = mock(RagVectorPolicy.class);
        RagQueryService service = new RagQueryService(jdbcTemplate, embeddingService, vectorPolicy);

        when(embeddingService.canVectorSearch()).thenReturn(true);
        when(vectorPolicy.publicSearchEnabledFor("BUILD_RECOMMEND")).thenReturn(true);
        when(embeddingService.embedQuery("QHD 게임 견적")).thenReturn(List.of(0.1, 0.2));
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of())
                .thenReturn(List.of(Map.of(
                        "id", "evidence-1",
                        "source_id", "build-guide-qhd",
                        "chunk_text", "QHD 게임 견적 근거",
                        "summary", "QHD 게임용 GPU 기준",
                        "score", 1.0,
                        "metadata", "{}"
                )));
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
                .thenReturn(0)
                .thenReturn(1);

        Map<String, Object> result = service.search("QHD 게임 견적", "BUILD_RECOMMEND", null, 0, 10);

        assertThat(result.get("total")).isEqualTo(1);
        assertThat(result.get("items")).asList().hasSize(1);
    }
}

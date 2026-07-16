package com.buildgraph.prototype.part.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.jdbc.core.JdbcTemplate;

class PartQueryCachedLoaderTest {

    private static final String CPU_ID = "10000000-0000-4000-8000-000000000001";
    private static final String GPU_ID = "10000000-0000-4000-8000-000000000002";

    @Test
    void cacheMissesUseOneBatchQueryAndRestoreRequestedOrder() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        cacheManager.setCaffeine(Caffeine.newBuilder().maximumSize(100));
        PartQueryCachedLoader loader = new PartQueryCachedLoader(cacheManager, jdbcTemplate);

        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(
                row(2L, GPU_ID, "GPU"),
                row(1L, CPU_ID, "CPU")
        ));

        List<ToolBuildPart> first = loader.partsByPublicIds(List.of(CPU_ID, GPU_ID));
        List<ToolBuildPart> second = loader.partsByPublicIds(List.of(GPU_ID, CPU_ID));

        assertThat(first).extracting(ToolBuildPart::publicId).containsExactly(CPU_ID, GPU_ID);
        assertThat(second).extracting(ToolBuildPart::publicId).containsExactly(GPU_ID, CPU_ID);
        assertThat(loader.dbQueryCount()).isEqualTo(1);
        verify(jdbcTemplate, times(1)).queryForList(anyString(), any(Object[].class));
    }

    private static Map<String, Object> row(long internalId, String publicId, String category) {
        return Map.of(
                "internal_id", internalId,
                "id", publicId,
                "category", category,
                "name", category + " part",
                "manufacturer", "BuildGraph",
                "price", 100_000,
                "attributes", Map.of(),
                "quantity", 1
        );
    }
}

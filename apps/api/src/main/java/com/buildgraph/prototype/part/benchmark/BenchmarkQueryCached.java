package com.buildgraph.prototype.part.benchmark;

import com.buildgraph.prototype.part.util.PartQueryUtil;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BenchmarkQueryCached {

    public static final String CACHE_NAME = "benchmark-summary";

    private final CacheManager cacheManager;
    private final JdbcTemplate jdbcTemplate;

    public BenchmarkQueryCached(CacheManager cacheManager, JdbcTemplate jdbcTemplate) {
        this.cacheManager = cacheManager;
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<Long, Map<String, Object>> latestBenchmarkInfos(List<Long> requestedPartIds) {
        if (requestedPartIds == null || requestedPartIds.isEmpty()) {
            return Map.of();
        }
        List<Long> partIds = requestedPartIds.stream().filter(Objects::nonNull).distinct().toList();
        Cache cache = cacheManager.getCache(CACHE_NAME);
        if (cache == null) {
            return findBenchmarksByIds(partIds);
        }

        /* 1. 부품별 캐시 hit와 miss를 분리한다. */
        Map<Long, Map<String, Object>> loaded = new HashMap<>();
        List<Long> missedIds = new ArrayList<>();
        for (Long partId : partIds) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cached = cache.get(partId, Map.class);
            if (cached == null) {
                missedIds.add(partId);
            } else {
                loaded.put(partId, cached);
            }
        }

        /* 2. 최신 benchmark도 캐시 miss ID 전체를 한 쿼리로 조회한다. */
        if (!missedIds.isEmpty()) {
            findBenchmarksByIds(missedIds).forEach((partId, benchmark) -> {
                loaded.put(partId, benchmark);
                cache.put(partId, benchmark);
            });
        }
        return loaded;
    }

    private Map<Long, Map<String, Object>> findBenchmarksByIds(List<Long> partIds) {
        if (partIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(partIds.size(), "?"));
        Map<Long, Map<String, Object>> result = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                        SELECT DISTINCT ON (part_id)
                               part_id,
                               summary,
                               score
                        FROM benchmark_summaries
                        WHERE part_id IN (
                        """ + placeholders + """
                        )
                          AND deleted_at IS NULL
                        ORDER BY part_id, created_at DESC, id DESC
                        """, partIds.toArray())
                .forEach(row -> result.put(PartQueryUtil.numberLong(row.get("part_id")), row));
        return result;
    }
}

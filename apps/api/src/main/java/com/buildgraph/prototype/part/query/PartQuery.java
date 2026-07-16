package com.buildgraph.prototype.part.query;

import com.buildgraph.prototype.part.tool.ToolBuildPart;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PartQuery {

    private final JdbcTemplate jdbcTemplate;
    private final PartQueryCachedLoader cachedLoader;

    public PartQuery(JdbcTemplate jdbcTemplate, PartQueryCachedLoader cachedLoader) {
        this.jdbcTemplate = jdbcTemplate;
        this.cachedLoader = cachedLoader;
    }

    /* public ID 기반 부품 조회는 이 진입점으로 통일한다. */
    public List<ToolBuildPart> partsByPublicIds(List<String> partIds) {
        return cachedLoader.partsByPublicIds(partIds);
    }

    /* draft ID -> part ID/수량 -> 캐시 가능한 부품 본문 순서로 조회한다. */
    public List<ToolBuildPart> partsByDraftId(Long draftId) {
        List<Map<String, Object>> draftItems = jdbcTemplate.queryForList("""
                SELECT p.public_id::text AS part_id,
                       qdi.quantity
                FROM quote_draft_items qdi
                JOIN parts p ON p.id = qdi.part_id
                WHERE qdi.quote_draft_id = ?
                  AND qdi.deleted_at IS NULL
                  AND p.deleted_at IS NULL
                ORDER BY qdi.created_at ASC, qdi.id ASC
                """, draftId);
        if (draftItems.isEmpty()) {
            return List.of();
        }

        List<String> partIds = draftItems.stream().map(row -> String.valueOf(row.get("part_id"))).toList();
        List<ToolBuildPart> parts = cachedLoader.partsByPublicIds(partIds);
        Map<String, Integer> quantities = new LinkedHashMap<>();
        for (Map<String, Object> item : draftItems) {
            Object value = item.get("quantity");
            quantities.put(String.valueOf(item.get("part_id")), value instanceof Number number ? number.intValue() : 1);
        }
        return parts.stream()
                .map(part -> new ToolBuildPart(
                        part.internalId(),
                        part.publicId(),
                        part.category(),
                        part.name(),
                        part.manufacturer(),
                        part.price(),
                        part.attributes(),
                        quantities.getOrDefault(part.publicId(), 1)
                ))
                .toList();
    }
}

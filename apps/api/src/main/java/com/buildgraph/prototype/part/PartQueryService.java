package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@Service
public class PartQueryService {
    private static final Set<String> CATEGORIES = Set.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    private static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE", "DISCONTINUED");
    private final JdbcTemplate jdbcTemplate;
    private final NaverShoppingOfferService naverShoppingOfferService;

    public PartQueryService(JdbcTemplate jdbcTemplate, NaverShoppingOfferService naverShoppingOfferService) {
        this.jdbcTemplate = jdbcTemplate;
        this.naverShoppingOfferService = naverShoppingOfferService;
    }

    public Map<String, Object> parts(
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            Integer page,
            Integer size,
            String sort
    ) {
        PartSearch search = new PartSearch(category, query, manufacturer, status, minPrice, maxPrice, page, size, sort);
        return MockData.map(
                "items", partRows(search),
                "page", search.page(),
                "size", search.size(),
                "total", countParts(search)
        );
    }

    public Map<String, Object> part(String id) {
        return jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               ps.source AS latest_price_source,
                               ps.collected_at AS latest_price_collected_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        WHERE p.public_id = ?::uuid
                          AND p.deleted_at IS NULL
                        """, id)
                .stream()
                .findFirst()
                .map(this::partMap)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "부품을 찾을 수 없습니다."));
    }

    public Map<String, Object> toolResult(String toolName) {
        Map<String, Object> rule = ruleFor(toolName);
        String status = rule == null ? defaultStatus(toolName) : DbValueMapper.string(rule, "status");
        String summary = rule == null ? "DB seed result for " + toolName : DbValueMapper.string(rule, "summary");
        return MockData.map(
                "status", status,
                "confidence", "MEDIUM",
                "summary", summary,
                "details", MockData.map(
                        "checkedPartIds", partRows(PartSearch.defaults()).stream().limit(3).map(part -> part.get("id")).toList(),
                        "source", "db-seed",
                        "toolName", toolName
                )
        );
    }

    private List<Map<String, Object>> partRows(PartSearch search) {
        SqlWhere where = whereClause(search);
        List<Object> params = new ArrayList<>(where.params());
        params.add(search.size());
        params.add(search.offset());
        return jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               ps.source AS latest_price_source,
                               ps.collected_at AS latest_price_collected_at
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT b.summary, b.score
                          FROM benchmark_summaries b
                          WHERE b.part_id = p.id
                            AND b.deleted_at IS NULL
                          ORDER BY b.created_at DESC, b.id DESC
                          LIMIT 1
                        ) bs ON true
                        LEFT JOIN LATERAL (
                          SELECT snapshot.source, snapshot.collected_at
                          FROM price_snapshots snapshot
                          WHERE snapshot.part_id = p.id
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        """ + where.sql() + " ORDER BY " + orderBy(search.sort()) + " LIMIT ? OFFSET ?",
                        params.toArray())
                .stream()
                .map(this::partMap)
                .toList();
    }

    private Integer countParts(PartSearch search) {
        SqlWhere where = whereClause(search);
        return jdbcTemplate.queryForObject("""
                SELECT count(*)
                FROM parts p
                """ + where.sql(), Integer.class, where.params().toArray());
    }

    private Map<String, Object> partMap(Map<String, Object> row) {
        return MockData.map(
                "id", DbValueMapper.string(row, "id"),
                "category", DbValueMapper.string(row, "category"),
                "name", DbValueMapper.string(row, "name"),
                "manufacturer", DbValueMapper.string(row, "manufacturer"),
                "price", DbValueMapper.integer(row, "price"),
                "status", DbValueMapper.string(row, "status"),
                "attributes", DbValueMapper.json(row, "attributes", Map.of()),
                "benchmarkSummary", benchmarkSummary(row),
                "latestPriceSource", DbValueMapper.string(row, "latest_price_source"),
                "latestPriceCollectedAt", DbValueMapper.timestamp(row, "latest_price_collected_at"),
                "externalOffer", naverShoppingOfferService.offerFor(
                        DbValueMapper.string(row, "id"),
                        DbValueMapper.string(row, "name"),
                        DbValueMapper.string(row, "manufacturer")
                ).orElse(null)
        );
    }

    private static Map<String, Object> benchmarkSummary(Map<String, Object> row) {
        String summary = DbValueMapper.string(row, "benchmark_summary");
        if (summary == null) {
            return null;
        }
        return MockData.map("summary", summary, "score", row.get("benchmark_score"));
    }

    private static SqlWhere whereClause(PartSearch search) {
        List<String> clauses = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        clauses.add("p.deleted_at IS NULL");
        if (search.category() != null) {
            clauses.add("p.category = ?");
            params.add(search.category());
        }
        if (search.status() != null) {
            clauses.add("p.status = ?");
            params.add(search.status());
        }
        if (search.manufacturer() != null) {
            clauses.add("lower(p.manufacturer) LIKE lower(concat('%', ?, '%'))");
            params.add(search.manufacturer());
        }
        if (search.query() != null) {
            clauses.add("""
                    (
                      lower(p.name) LIKE lower(concat('%', ?, '%'))
                      OR lower(coalesce(p.manufacturer, '')) LIKE lower(concat('%', ?, '%'))
                      OR lower(coalesce(p.attributes::text, '')) LIKE lower(concat('%', ?, '%'))
                    )
                    """);
            params.add(search.query());
            params.add(search.query());
            params.add(search.query());
        }
        if (search.minPrice() != null) {
            clauses.add("p.price >= ?");
            params.add(search.minPrice());
        }
        if (search.maxPrice() != null) {
            clauses.add("p.price <= ?");
            params.add(search.maxPrice());
        }
        return new SqlWhere("WHERE " + String.join(" AND ", clauses), params);
    }

    private static String orderBy(String sort) {
        return switch (sort) {
            case "price_asc" -> "p.price ASC, p.id ASC";
            case "price_desc" -> "p.price DESC, p.id ASC";
            case "name" -> "p.name ASC, p.id ASC";
            default -> """
                    CASE p.category
                      WHEN 'CPU' THEN 1
                      WHEN 'MOTHERBOARD' THEN 2
                      WHEN 'RAM' THEN 3
                      WHEN 'GPU' THEN 4
                      WHEN 'STORAGE' THEN 5
                      WHEN 'PSU' THEN 6
                      WHEN 'CASE' THEN 7
                      WHEN 'COOLER' THEN 8
                      ELSE 99
                    END,
                    p.id ASC
                    """;
        };
    }

    private Map<String, Object> ruleFor(String toolName) {
        String category = switch (toolName) {
            case "compatibility" -> "CPU";
            case "size" -> "GPU";
            case "power" -> "PSU";
            case "performance" -> "GPU";
            case "price" -> "GPU";
            default -> "CPU";
        };
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT result_status AS status, message AS summary
                FROM compatibility_rules
                WHERE category = ?
                  AND deleted_at IS NULL
                ORDER BY id
                LIMIT 1
                """, category);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private static String defaultStatus(String toolName) {
        return "compatibility".equals(toolName) || "size".equals(toolName) ? "PASS" : "WARN";
    }

    private record SqlWhere(String sql, List<Object> params) {
    }

    private record PartSearch(
            String category,
            String query,
            String manufacturer,
            String status,
            Integer minPrice,
            Integer maxPrice,
            int page,
            int size,
            String sort
    ) {
        private PartSearch(
                String category,
                String query,
                String manufacturer,
                String status,
                Integer minPrice,
                Integer maxPrice,
                Integer page,
                Integer size,
                String sort
        ) {
            this(
                    normalizeEnum(category, CATEGORIES, "지원하지 않는 부품 category입니다."),
                    blankToNull(query),
                    blankToNull(manufacturer),
                    normalizeStatus(status),
                    positiveOrNull(minPrice, "minPrice는 0 이상이어야 합니다."),
                    positiveOrNull(maxPrice, "maxPrice는 0 이상이어야 합니다."),
                    page == null ? 0 : Math.max(page, 0),
                    size == null ? 20 : Math.min(Math.max(size, 1), 100),
                    normalizeSort(sort)
            );
            if (this.minPrice != null && this.maxPrice != null && this.minPrice > this.maxPrice) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "minPrice는 maxPrice보다 클 수 없습니다.");
            }
        }

        static PartSearch defaults() {
            return new PartSearch(null, null, null, null, null, null, 0, 20, null);
        }

        int offset() {
            return page * size;
        }
    }

    private static String normalizeStatus(String value) {
        String normalized = normalizeEnum(value, STATUSES, "지원하지 않는 부품 status입니다.");
        return normalized == null ? "ACTIVE" : normalized;
    }

    private static String normalizeEnum(String value, Set<String> allowedValues, String message) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return null;
        }
        String upper = normalized.toUpperCase();
        if (!allowedValues.contains(upper)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return upper;
    }

    private static String normalizeSort(String value) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            return "category";
        }
        if (Set.of("category", "price_asc", "price_desc", "name").contains(normalized)) {
            return normalized;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 sort입니다.");
    }

    private static Integer positiveOrNull(Integer value, String message) {
        if (value == null) {
            return null;
        }
        if (value < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

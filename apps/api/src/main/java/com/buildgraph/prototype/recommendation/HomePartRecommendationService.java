package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HomePartRecommendationService {
    private static final Logger log = LoggerFactory.getLogger(HomePartRecommendationService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private final JdbcTemplate jdbcTemplate;
    private final RecommendationScoringClient scoringClient;
    private final RecommendationModelRegistry modelRegistry;
    private final boolean shadowEnabled;

    public HomePartRecommendationService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            RecommendationModelRegistry modelRegistry,
            @Value("${recommendation.reranker.shadow-enabled:true}") boolean shadowEnabled
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.scoringClient = scoringClient;
        this.modelRegistry = modelRegistry;
        this.shadowEnabled = shadowEnabled;
    }

    public Map<String, Object> homeParts(CurrentUserService.CurrentUser user, Integer limit) {
        int safeLimit = limit == null ? 4 : Math.min(Math.max(limit, 1), 12);
        List<HomePartCandidate> candidates = loadCandidates();
        if (candidates.isEmpty()) {
            return MockData.map(
                    "items", List.of(),
                    "generatedAt", Instant.now().toString(),
                    "fallbackUsed", true
            );
        }
        ScoringOutcome scoring = scoreCandidates(user, candidates, safeLimit);
        List<HomePartCandidate> ranked = candidates.stream()
                .sorted(Comparator
                        .<HomePartCandidate>comparingDouble(HomePartCandidate::score).reversed()
                        .thenComparingInt(candidate -> categoryRank(candidate.category()))
                        .thenComparing(HomePartCandidate::publicId))
                .toList();
        List<HomePartCandidate> selected = diverseTop(ranked, safeLimit);
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < selected.size(); index += 1) {
            HomePartCandidate candidate = selected.get(index);
            items.add(MockData.map(
                    "recommendationId", "home-part-" + candidate.publicId(),
                    "rankPosition", index,
                    "part", partMap(candidate.row()),
                    "scoreSource", scoring.scoreSource(),
                    "modelVersion", scoring.modelVersion(),
                    "reasonTags", reasonTags(candidate, scoring)
            ));
        }
        return MockData.map(
                "items", items,
                "generatedAt", Instant.now().toString(),
                "fallbackUsed", scoring.fallbackUsed()
        );
    }

    private List<HomePartCandidate> loadCandidates() {
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.status,
                               p.attributes,
                               bs.summary AS benchmark_summary,
                               bs.score AS benchmark_score,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.source
                                 ELSE ps.source
                               END AS latest_price_source,
                               CASE
                                 WHEN peo.low_price IS NOT NULL AND peo.low_price = p.price THEN peo.refreshed_at
                                 ELSE ps.collected_at
                               END AS latest_price_collected_at,
                               peo.title AS external_offer_title,
                               peo.image_url AS external_offer_image_url,
                               peo.supplier_name AS external_offer_supplier_name,
                               peo.offer_url AS external_offer_url,
                               peo.low_price AS external_offer_low_price,
                               peo.source AS external_offer_source,
                               peo.refreshed_at AS external_offer_refreshed_at,
                               CASE
                                 WHEN ps.collected_at IS NULL THEN NULL
                                 ELSE extract(epoch FROM (now() - ps.collected_at)) / 86400.0
                               END AS price_age_days,
                               EXISTS (
                                 SELECT 1
                                 FROM game_fps_benchmarks fps
                                 WHERE fps.cpu_part_id = p.id OR fps.gpu_part_id = p.id
                               ) AS has_fps_coverage
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
                            AND snapshot.collected_at <= now()
                          ORDER BY snapshot.collected_at DESC, snapshot.id DESC
                          LIMIT 1
                        ) ps ON true
                        LEFT JOIN LATERAL (
                          SELECT offer.*
                          FROM part_external_offers offer
                          WHERE offer.part_id = p.id
                            AND offer.deleted_at IS NULL
                          ORDER BY
                            CASE offer.source
                              WHEN 'NAVER_SHOPPING_SEARCH' THEN 1
                              WHEN 'ADMIN_MANUAL' THEN 2
                              ELSE 9
                            END,
                            offer.refreshed_at DESC,
                            offer.id DESC
                          LIMIT 1
                        ) peo ON true
                        WHERE p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                          AND p.price IS NOT NULL
                          AND p.price > 0
                        """).stream()
                .map(row -> new HomePartCandidate(row, deterministicScore(row)))
                .toList();
    }

    private ScoringOutcome scoreCandidates(CurrentUserService.CurrentUser user, List<HomePartCandidate> candidates, int limit) {
        if (!shadowEnabled) {
            return new ScoringOutcome("FALLBACK", null, true);
        }
        try {
            String requestHash = sha256(OBJECT_MAPPER.writeValueAsString(MockData.map(
                    "surface", "HOME_RECOMMENDED_PARTS",
                    "limit", limit,
                    "candidateIds", candidates.stream().map(HomePartCandidate::publicId).toList()
            )));
            List<Map<String, Object>> payloadCandidates = new ArrayList<>();
            for (int index = 0; index < candidates.size(); index += 1) {
                HomePartCandidate candidate = candidates.get(index);
                candidate.withRankPosition(index);
                payloadCandidates.add(MockData.map(
                        "candidateType", "PART",
                        "candidateId", candidate.publicId(),
                        "partId", candidate.publicId(),
                        "rankPosition", index,
                        "features", candidate.features(index)
                ));
            }
            Map<String, Object> scorerResponse = scoringClient.score(scoringClient.payload(
                    requestHash,
                    "HOME_PARTS",
                    false,
                    payloadCandidates
            ));
            List<Map<String, Object>> scores = objectMaps(scorerResponse.get("scores"));
            if (scores.isEmpty()) {
                return new ScoringOutcome("FALLBACK", null, true);
            }
            Long modelVersionId = modelRegistry.upsertShadowModelVersion(scorerResponse);
            boolean matchedAnyScore = false;
            Map<String, HomePartCandidate> byPartId = new LinkedHashMap<>();
            for (HomePartCandidate candidate : candidates) {
                byPartId.put(candidate.publicId(), candidate);
            }
            for (Map<String, Object> scoreRow : scores) {
                HomePartCandidate candidate = byPartId.get(firstText(text(scoreRow.get("partId")), text(scoreRow.get("candidateId"))));
                Double score = decimal(scoreRow.get("score"));
                if (candidate == null || score == null) {
                    continue;
                }
                matchedAnyScore = true;
                candidate.score(score);
                jdbcTemplate.update("""
                        INSERT INTO recommendation_shadow_scores (
                          user_id,
                          model_version_id,
                          source_surface,
                          request_hash,
                          candidate_type,
                          candidate_id,
                          part_id,
                          score,
                          rank_position,
                          features,
                          raw_response
                        )
                        VALUES (?, ?, 'HOME_RECOMMENDED_PARTS', ?, 'PART', ?, ?, ?, ?, ?::jsonb, ?::jsonb)
                        """,
                        user.internalId(),
                        modelVersionId,
                        requestHash,
                        candidate.publicId(),
                        candidate.internalId(),
                        score,
                        candidate.rankPosition(),
                        OBJECT_MAPPER.writeValueAsString(candidate.features(candidate.rankPosition())),
                        OBJECT_MAPPER.writeValueAsString(scoreRow)
                );
            }
            if (!matchedAnyScore) {
                return new ScoringOutcome("FALLBACK", null, true);
            }
            String modelVersion = text(scorerResponse.get("modelVersion"));
            if ("baseline-shadow".equals(modelVersion)) {
                return new ScoringOutcome("FALLBACK", null, true);
            }
            return new ScoringOutcome("XGBOOST", modelVersion, false);
        } catch (Exception error) {
            log.warn("Home part XGBoost scoring skipped: {}", error.getMessage());
            return new ScoringOutcome("FALLBACK", null, true);
        }
    }

    private static List<HomePartCandidate> diverseTop(List<HomePartCandidate> ranked, int limit) {
        List<HomePartCandidate> selected = new ArrayList<>();
        Set<String> usedCategories = new java.util.HashSet<>();
        for (HomePartCandidate candidate : ranked) {
            if (selected.size() >= limit) {
                return selected;
            }
            if (usedCategories.add(candidate.category())) {
                selected.add(candidate.withRankPosition(selected.size()));
            }
        }
        for (HomePartCandidate candidate : ranked) {
            if (selected.size() >= limit) {
                return selected;
            }
            if (selected.stream().noneMatch(existing -> existing.publicId().equals(candidate.publicId()))) {
                selected.add(candidate.withRankPosition(selected.size()));
            }
        }
        return selected;
    }

    private static double deterministicScore(Map<String, Object> row) {
        double score = 0.0;
        score += number(row.get("benchmark_score"), 0.0);
        score += booleanValue(row.get("has_fps_coverage")) ? 8.0 : 0.0;
        score += text(row.get("external_offer_image_url")) == null ? 0.0 : 10.0;
        score += text(row.get("external_offer_url")) == null ? 0.0 : 5.0;
        score += toolReady(row) ? 8.0 : 0.0;
        Double priceAgeDays = decimal(row.get("price_age_days"));
        if (priceAgeDays != null) {
            score += Math.max(0.0, 10.0 - Math.min(priceAgeDays, 30.0) / 3.0);
        }
        Integer price = DbValueMapper.integer(row, "price");
        if (price != null && price > 0) {
            score -= Math.min(price / 2_000_000.0, 4.0);
        }
        score += Math.max(0, 8 - categoryRank(DbValueMapper.string(row, "category"))) * 0.01;
        return score;
    }

    private static List<String> reasonTags(HomePartCandidate candidate, ScoringOutcome scoring) {
        List<String> tags = new ArrayList<>();
        if (number(candidate.row().get("benchmark_score"), 0.0) > 0) {
            tags.add("benchmark");
        }
        if (booleanValue(candidate.row().get("has_fps_coverage"))) {
            tags.add("fps");
        }
        if (text(candidate.row().get("external_offer_image_url")) != null) {
            tags.add("image");
        }
        if (toolReady(candidate.row())) {
            tags.add("toolReady");
        }
        Double priceAgeDays = decimal(candidate.row().get("price_age_days"));
        if (priceAgeDays != null && priceAgeDays <= 7.0) {
            tags.add("freshPrice");
        }
        if ("XGBOOST".equals(scoring.scoreSource())
                && scoring.modelVersion() != null
                && !"baseline-shadow".equals(scoring.modelVersion())) {
            tags.add("userReaction");
        }
        if (tags.isEmpty()) {
            tags.add("internalAsset");
        }
        return tags;
    }

    private static Map<String, Object> partMap(Map<String, Object> row) {
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
                "externalOffer", externalOffer(row)
        );
    }

    private static Map<String, Object> benchmarkSummary(Map<String, Object> row) {
        String summary = DbValueMapper.string(row, "benchmark_summary");
        if (summary == null && row.get("benchmark_score") == null) {
            return null;
        }
        return MockData.map("summary", summary, "score", row.get("benchmark_score"));
    }

    private static Map<String, Object> externalOffer(Map<String, Object> row) {
        if (DbValueMapper.string(row, "external_offer_title") == null
                && DbValueMapper.string(row, "external_offer_image_url") == null
                && DbValueMapper.string(row, "external_offer_url") == null) {
            return null;
        }
        return MockData.map(
                "title", DbValueMapper.string(row, "external_offer_title"),
                "imageUrl", DbValueMapper.string(row, "external_offer_image_url"),
                "supplierName", DbValueMapper.string(row, "external_offer_supplier_name"),
                "offerUrl", DbValueMapper.string(row, "external_offer_url"),
                "lowPrice", DbValueMapper.integer(row, "external_offer_low_price"),
                "source", DbValueMapper.string(row, "external_offer_source"),
                "refreshedAt", DbValueMapper.timestamp(row, "external_offer_refreshed_at")
        );
    }

    @SuppressWarnings("unchecked")
    private static boolean toolReady(Map<String, Object> row) {
        Object value = row.get("attributes");
        Map<String, Object> attributes;
        if (value instanceof Map<?, ?> map) {
            attributes = (Map<String, Object>) map;
        } else {
            Object parsed = DbValueMapper.json(row, "attributes", Map.of());
            attributes = parsed instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
        }
        return booleanValue(attributes.get("toolReady"));
    }

    private static int categoryRank(String category) {
        return switch (category) {
            case "CPU" -> 1;
            case "MOTHERBOARD" -> 2;
            case "RAM" -> 3;
            case "GPU" -> 4;
            case "STORAGE" -> 5;
            case "PSU" -> 6;
            case "CASE" -> 7;
            case "COOLER" -> 8;
            default -> 99;
        };
    }

    private static String sha256(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte b : hash) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                map.forEach((key, mapValue) -> row.put(String.valueOf(key), mapValue));
                result.add(row);
            }
        }
        return result;
    }

    private static Double decimal(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null || value.toString().isBlank()) {
            return null;
        }
        return Double.valueOf(value.toString());
    }

    private static double number(Object value, double fallback) {
        Double parsed = decimal(value);
        return parsed == null ? fallback : parsed;
    }

    private static boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        return value != null && Boolean.parseBoolean(value.toString());
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private record ScoringOutcome(String scoreSource, String modelVersion, boolean fallbackUsed) {
    }

    private static final class HomePartCandidate {
        private final Map<String, Object> row;
        private double score;
        private int rankPosition;

        private HomePartCandidate(Map<String, Object> row, double score) {
            this.row = row;
            this.score = score;
            this.rankPosition = 0;
        }

        private Map<String, Object> row() {
            return row;
        }

        private String publicId() {
            return DbValueMapper.string(row, "id");
        }

        private Long internalId() {
            Object value = row.get("internal_id");
            if (value instanceof Number number) {
                return number.longValue();
            }
            return null;
        }

        private String category() {
            return DbValueMapper.string(row, "category");
        }

        private double score() {
            return score;
        }

        private void score(double score) {
            this.score = score;
        }

        private int rankPosition() {
            return rankPosition;
        }

        private HomePartCandidate withRankPosition(int rankPosition) {
            this.rankPosition = rankPosition;
            return this;
        }

        private Map<String, Object> features(int rankPosition) {
            Integer price = DbValueMapper.integer(row, "price");
            Map<String, Object> features = new LinkedHashMap<>(MockData.map(
                    "rank_position", rankPosition,
                    "part_price", price,
                    "price", price,
                    "category", category(),
                    "part_benchmark_score", row.get("benchmark_score"),
                    "benchmark_score", row.get("benchmark_score"),
                    "part_has_image", text(row.get("external_offer_image_url")) != null,
                    "has_image", text(row.get("external_offer_image_url")) != null,
                    "part_has_offer", text(row.get("external_offer_url")) != null,
                    "has_offer", text(row.get("external_offer_url")) != null,
                    "part_price_age_days", row.get("price_age_days"),
                    "price_age_days", row.get("price_age_days"),
                    "part_tool_ready", toolReady(row),
                    "tool_ready", toolReady(row),
                    "part_has_fps_coverage", booleanValue(row.get("has_fps_coverage")),
                    "fps_coverage", booleanValue(row.get("has_fps_coverage"))
            ));
            for (String category : List.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER")) {
                features.put("category_" + category, category.equals(category()) ? 1 : 0);
            }
            return features;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof HomePartCandidate candidate && Objects.equals(publicId(), candidate.publicId());
        }

        @Override
        public int hashCode() {
            return Objects.hash(publicId());
        }
    }
}

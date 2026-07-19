package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class HomePartRecommendationService {
    private static final Logger log = LoggerFactory.getLogger(HomePartRecommendationService.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // 홈 부품 카드가 서빙 시 배출하는 부품 피처 계약 키. 학습 스냅샷·스코어러 FEATURES와 이름·의미가
    // 일치해야 한다(학습-서빙 스큐 방지, M6). 이 목록은 features() 빌더가 실제 배출하는 언더스코어
    // 계약 키와 동일하게 유지해야 하며, FeatureContractTest가 스코어러 FEATURES와의 정합을 고정한다.
    static final List<String> SERVING_CATEGORIES =
            List.of("CPU", "GPU", "RAM", "MOTHERBOARD", "STORAGE", "PSU", "CASE", "COOLER");
    static final List<String> SERVING_PART_FEATURE_KEYS = List.of(
            "part_price", "part_benchmark_score", "part_tool_ready", "part_has_image",
            "part_has_offer", "part_price_age_days", "part_has_fps_coverage");
    private final JdbcTemplate jdbcTemplate;
    private final RecommendationScoringClient scoringClient;
    private final RecommendationModelRegistry modelRegistry;
    private final boolean shadowEnabled;
    private final Executor shadowExecutor;
    private final long shadowThrottleMs;
    // 스코어러가 실모델을 서빙 중인지에 대한 마지막 관측. null=미확인(동기 1회 탐지),
    // FALSE=baseline(홈 응답을 블로킹하지 않고 비동기 shadow만), TRUE=실모델(순위 반영 위해 동기).
    private volatile Boolean scorerServingRealModel;
    // 동기 스코어 실패 백오프 — 스코어러가 죽어 있으면 요청마다 타임아웃(reranker.timeout-ms)을
    // 물게 되므로, 실패 후 이 시간 동안은 동기 호출 없이 즉시 FALLBACK한다.
    private static final long SCORER_FAILURE_BACKOFF_MS = 60_000L;
    private volatile long lastScorerFailureAtMs;
    // request_hash별 최근 shadow 기록 시각 — 같은 후보 집합의 연속 새로고침이 scorer 호출과
    // shadow 테이블 행을 무한 증식시키지 않도록 스로틀한다.
    private final Map<String, Long> recentShadowRequests = new ConcurrentHashMap<>();
    // ACTIVE 후보 전수 스캔(3 LATERAL + FPS EXISTS) 결과 row의 단기 캐시 — 사용자와 무관한 데이터인데
    // 인증 홈추천이 요청마다 재실행하던 최중량 쿼리다. row는 읽기 전용으로 공유하고,
    // rankPosition/score를 변이하는 HomePartCandidate 래퍼는 요청마다 새로 만든다(공유 변이 방지).
    private final ReadThroughTtlCache<String, List<Map<String, Object>>> candidateRowsCache;
    private static final String CANDIDATE_ROWS_KEY = "active-candidates";

    @Autowired
    public HomePartRecommendationService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            RecommendationModelRegistry modelRegistry,
            @Value("${recommendation.reranker.shadow-enabled:true}") boolean shadowEnabled,
            @Value("${recommendation.reranker.shadow-throttle-ms:300000}") long shadowThrottleMs,
            @Value("${recommendation.home-parts.candidate-cache.ttl-seconds:30}") long candidateCacheTtlSeconds
    ) {
        this(jdbcTemplate, scoringClient, modelRegistry, shadowEnabled,
                Executors.newSingleThreadExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "home-shadow-recorder");
                    thread.setDaemon(true);
                    return thread;
                }),
                shadowThrottleMs,
                candidateCacheTtlSeconds);
    }

    HomePartRecommendationService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            RecommendationModelRegistry modelRegistry,
            boolean shadowEnabled
    ) {
        // 테스트용: 비동기 경로를 같은 스레드에서 실행하고 스로틀·후보 캐시를 끈다(기존 단건 동작 그대로).
        this(jdbcTemplate, scoringClient, modelRegistry, shadowEnabled, Runnable::run, 0L, 0L);
    }

    HomePartRecommendationService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            RecommendationModelRegistry modelRegistry,
            boolean shadowEnabled,
            Executor shadowExecutor,
            long shadowThrottleMs
    ) {
        this(jdbcTemplate, scoringClient, modelRegistry, shadowEnabled, shadowExecutor, shadowThrottleMs, 0L);
    }

    HomePartRecommendationService(
            JdbcTemplate jdbcTemplate,
            RecommendationScoringClient scoringClient,
            RecommendationModelRegistry modelRegistry,
            boolean shadowEnabled,
            Executor shadowExecutor,
            long shadowThrottleMs,
            long candidateCacheTtlSeconds
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.scoringClient = scoringClient;
        this.modelRegistry = modelRegistry;
        this.shadowEnabled = shadowEnabled;
        this.shadowExecutor = shadowExecutor;
        this.shadowThrottleMs = shadowThrottleMs;
        this.candidateRowsCache = new ReadThroughTtlCache<>(Duration.ofSeconds(candidateCacheTtlSeconds), 4);
    }

    /**
     * 모델 activate/retire 시 훈련 서비스가 호출해 서빙 모드를 즉시 갱신한다.
     * (미호출이어도 다음 스코어러 응답에서 자동 보정되지만, 승급 직후 반영 지연을 없앤다)
     */
    public void notifyScorerModelChanged(Boolean realModelActive) {
        this.scorerServingRealModel = realModelActive;
    }

    /**
     * 부팅 직후 스코어러 서빙 모드를 백그라운드에서 1회 판별한다 — 첫 홈 요청이 '미확인' 동기 판별
     * (스코어러 타임아웃까지 블로킹)을 떠안지 않게 한다. 실패하면 미확인(null) 유지: 기존처럼 첫
     * 요청이 판별하되, 그 실패도 백오프(60s)로 흡수돼 연쇄 블로킹은 없다.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void probeScorerServingModeOnStartup() {
        if (!shadowEnabled || scorerServingRealModel != null) {
            return;
        }
        shadowExecutor.execute(() -> {
            try {
                String modelVersion = text(scoringClient.health().get("modelVersion"));
                if (modelVersion != null) {
                    scorerServingRealModel = !"baseline-shadow".equals(modelVersion);
                }
            } catch (Exception error) {
                log.info("Scorer startup probe skipped: {}", error.getMessage());
            }
        });
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

    public Map<String, Object> publicHomeParts(Integer limit) {
        return sharedHomeParts(limit);
    }

    public Map<String, Object> sharedHomeParts(Integer limit) {
        int safeLimit = limit == null ? 4 : Math.min(Math.max(limit, 1), 12);
        List<HomePartCandidate> candidates = loadCandidates();
        if (candidates.isEmpty()) {
            return MockData.map(
                    "items", List.of(),
                    "generatedAt", Instant.now().toString(),
                    "fallbackUsed", true
            );
        }
        ScoringOutcome scoring = new ScoringOutcome("FALLBACK", null, true);
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
                "fallbackUsed", true
        );
    }
    private List<HomePartCandidate> loadCandidates() {
        // row는 캐시에서 공유(불변 취급), 변이 가능한 래퍼는 요청마다 새로 생성.
        return candidateRowsCache.get(CANDIDATE_ROWS_KEY, this::queryCandidateRows).stream()
                .map(row -> new HomePartCandidate(row, deterministicScore(row)))
                .toList();
    }

    private List<Map<String, Object>> queryCandidateRows() {
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
                        """);
    }

    private ScoringOutcome scoreCandidates(CurrentUserService.CurrentUser user, List<HomePartCandidate> candidates, int limit) {
        if (!shadowEnabled) {
            return new ScoringOutcome("FALLBACK", null, true);
        }
        try {
            String requestHash = requestHash(limit, candidates);
            if (Boolean.FALSE.equals(scorerServingRealModel)) {
                // 스코어러가 baseline임이 확인된 상태: 점수가 순위에 쓰이지 않으므로 홈 응답을
                // 스코어러 호출(≤1.2s)로 블로킹하지 않는다. shadow 수집은 백그라운드로 계속하되,
                // 스로틀 검사를 payload 생성 앞에서 해 통과분만 피처 직렬화 비용을 낸다.
                if (shadowThrottlePassed(requestHash)) {
                    recordShadowAsync(user, requestHash, candidates);
                }
                return new ScoringOutcome("FALLBACK", null, true);
            }
            if (System.currentTimeMillis() - lastScorerFailureAtMs < SCORER_FAILURE_BACKOFF_MS) {
                // 직전 동기 스코어 실패 후 백오프 구간 — 죽은 스코어러에 요청마다 타임아웃을 물지 않는다.
                return new ScoringOutcome("FALLBACK", null, true);
            }
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
            Map<String, HomePartCandidate> byPartId = new LinkedHashMap<>();
            for (HomePartCandidate candidate : candidates) {
                byPartId.put(candidate.publicId(), candidate);
            }
            // 스코어러 점수를 후보에 바로 덮어쓰지 않는다. 예전에는 여기서 candidate.score()를 덮어쓴 뒤
            // baseline-shadow 판정으로 FALLBACK을 리턴해도 점수가 복원되지 않아, scoreSource=FALLBACK인데
            // 실제 순위는 Python baseline 점수가 결정하는 거짓 표시가 됐다. 섀도우 기록은 그대로 남기고,
            // 실모델(XGBOOST) 판정이 확정된 뒤에만 순위 점수에 반영한다.
            Map<HomePartCandidate, Double> pendingScores = new LinkedHashMap<>();
            for (Map<String, Object> scoreRow : scores) {
                HomePartCandidate candidate = byPartId.get(firstText(text(scoreRow.get("partId")), text(scoreRow.get("candidateId"))));
                Double score = decimal(scoreRow.get("score"));
                if (candidate == null || score == null) {
                    continue;
                }
                pendingScores.put(candidate, score);
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
            if (pendingScores.isEmpty()) {
                return new ScoringOutcome("FALLBACK", null, true);
            }
            String modelVersion = text(scorerResponse.get("modelVersion"));
            if ("baseline-shadow".equals(modelVersion)) {
                // 후보 점수를 건드리지 않았으므로 FALLBACK 표시와 실제 순위(Java deterministicScore)가 일치한다.
                // baseline임을 기억해 다음 요청부터는 스코어러 호출로 홈 응답을 블로킹하지 않는다.
                scorerServingRealModel = false;
                return new ScoringOutcome("FALLBACK", null, true);
            }
            scorerServingRealModel = true;
            pendingScores.forEach(HomePartCandidate::score);
            return new ScoringOutcome("XGBOOST", modelVersion, false);
        } catch (Exception error) {
            lastScorerFailureAtMs = System.currentTimeMillis();
            log.warn("Home part XGBoost scoring skipped: {}", error.getMessage());
            return new ScoringOutcome("FALLBACK", null, true);
        }
    }

    // Jackson 직렬화 없이 같은 후보 집합을 식별한다(스로틀 키·shadow 행 그룹핑용 내부 값 —
    // 구분자 '|'는 UUID·정수에 없어 충돌 없음). 계약 아님: 형식 변경은 배포 직후 스로틀 1회 리셋으로만 나타난다.
    private static String requestHash(int limit, List<HomePartCandidate> candidates) throws Exception {
        StringBuilder joined = new StringBuilder("HOME_RECOMMENDED_PARTS|").append(limit);
        for (HomePartCandidate candidate : candidates) {
            joined.append('|').append(candidate.publicId());
        }
        return sha256(joined.toString());
    }

    // 같은 후보 집합의 연속 새로고침이 scorer 호출·shadow 행을 무한 증식시키지 않게 하는 스로틀.
    // 통과 시각을 먼저 기록하므로 이후 비동기 기록이 실패해도 스로틀 창 동안 재시도하지 않는다(의도).
    private boolean shadowThrottlePassed(String requestHash) {
        long now = System.currentTimeMillis();
        Long lastRecorded = recentShadowRequests.get(requestHash);
        if (lastRecorded != null && now - lastRecorded < shadowThrottleMs) {
            return false;
        }
        recentShadowRequests.put(requestHash, now);
        if (recentShadowRequests.size() > 512) {
            recentShadowRequests.entrySet().removeIf(entry -> now - entry.getValue() >= shadowThrottleMs);
        }
        return true;
    }

    // 비동기 shadow 기록용 스냅샷 — rankPosition은 루프 index로 고정하고 features JSON은 선직렬화한다.
    private record ShadowCandidateSnapshot(String publicId, Long internalId, int rankPosition, String featuresJson) {}

    private void recordShadowAsync(
            CurrentUserService.CurrentUser user,
            String requestHash,
            List<HomePartCandidate> candidates
    ) {
        Long userInternalId = user.internalId();
        shadowExecutor.execute(() -> {
            try {
                // payload·features 직렬화를 요청 스레드가 아닌 여기서 수행한다(스로틀 통과분만 비용 발생).
                // 후보의 row·ID는 불변 공유이고 rankPosition은 루프 index로 재계산하므로, 요청 스레드가
                // 이후 diverseTop에서 rankPosition을 변이해도 이 스레드가 읽는 값과 무관하다.
                List<Map<String, Object>> payloadCandidates = new ArrayList<>(candidates.size());
                List<ShadowCandidateSnapshot> snapshot = new ArrayList<>(candidates.size());
                for (int index = 0; index < candidates.size(); index += 1) {
                    HomePartCandidate candidate = candidates.get(index);
                    Map<String, Object> features = candidate.features(index);
                    payloadCandidates.add(MockData.map(
                            "candidateType", "PART",
                            "candidateId", candidate.publicId(),
                            "partId", candidate.publicId(),
                            "rankPosition", index,
                            "features", features
                    ));
                    snapshot.add(new ShadowCandidateSnapshot(
                            candidate.publicId(),
                            candidate.internalId(),
                            index,
                            OBJECT_MAPPER.writeValueAsString(features)
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
                    return;
                }
                Long modelVersionId = modelRegistry.upsertShadowModelVersion(scorerResponse);
                Map<String, ShadowCandidateSnapshot> byPartId = new LinkedHashMap<>();
                for (ShadowCandidateSnapshot row : snapshot) {
                    byPartId.put(row.publicId(), row);
                }
                for (Map<String, Object> scoreRow : scores) {
                    ShadowCandidateSnapshot row = byPartId.get(firstText(text(scoreRow.get("partId")), text(scoreRow.get("candidateId"))));
                    Double score = decimal(scoreRow.get("score"));
                    if (row == null || score == null) {
                        continue;
                    }
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
                            userInternalId,
                            modelVersionId,
                            requestHash,
                            row.publicId(),
                            row.internalId(),
                            score,
                            row.rankPosition(),
                            row.featuresJson(),
                            OBJECT_MAPPER.writeValueAsString(scoreRow)
                    );
                }
                // 백그라운드 관측으로도 서빙 모드를 자동 보정한다(예: 별도 경로로 모델이 activate된 경우).
                String modelVersion = text(scorerResponse.get("modelVersion"));
                if (modelVersion != null && !"baseline-shadow".equals(modelVersion)) {
                    scorerServingRealModel = true;
                }
            } catch (Exception error) {
                log.warn("Home part shadow recording skipped: {}", error.getMessage());
            }
        });
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
            // 결측 기본값은 훈련 스냅샷(RecommendationTrainingService.featureSnapshot)과 반드시 일치해야 한다.
            // 예전에는 여기서 null을 그대로 보내 Python이 0.0으로 치환 — 훈련(999=오래됨)과 정반대 의미가 됐다.
            Double priceAgeDays = decimal(row.get("price_age_days"));
            double priceAgeFeature = priceAgeDays == null ? 999.0 : priceAgeDays;
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
                    "part_price_age_days", priceAgeFeature,
                    "price_age_days", priceAgeFeature,
                    "part_tool_ready", toolReady(row),
                    "tool_ready", toolReady(row),
                    "part_has_fps_coverage", booleanValue(row.get("has_fps_coverage")),
                    "fps_coverage", booleanValue(row.get("has_fps_coverage"))
            ));
            for (String category : SERVING_CATEGORIES) {
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

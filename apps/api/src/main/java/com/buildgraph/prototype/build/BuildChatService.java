package com.buildgraph.prototype.build;

import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.agent.AiChatEngineRequest;
import com.buildgraph.prototype.agent.AiChatEngineResponse;
import com.buildgraph.prototype.agent.AiChatIntent;
import com.buildgraph.prototype.agent.AiChatAction;
import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.agent.PartReplacementRanker;
import com.buildgraph.prototype.agent.PartRouteResolver;
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import com.buildgraph.prototype.recommendation.CandidateReranker;
import com.buildgraph.prototype.recommendation.NoopCandidateReranker;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuildChatService {
    private static final Logger log = LoggerFactory.getLogger(BuildChatService.class);
    private static final Pattern BUDGET_MANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:만원|만)");
    private static final Pattern BUDGET_BAEKMANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*백\\s*만원");
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
    private static final Pattern EXPLICIT_GPU_MODEL = Pattern.compile("(?i)(?:rtx|geforce|지포스)?\\s*(40[6-9]0|50[6-9]0)(?:\\s*(ti|super))?");
    private static final Pattern EXPLICIT_CPU_MODEL = Pattern.compile("(?i)\\b\\d{4,5}x3d\\b|\\b\\d{4,5}x\\b|\\bi[3579]-?\\d{4,5}\\b");
    private static final Pattern CAPACITY_GB_PATTERN = Pattern.compile("(\\d+)\\s*(?:gb|기가|기가바이트)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WATT_PATTERN = Pattern.compile("(\\d{3,4})\\s*w", Pattern.CASE_INSENSITIVE);
    private static final List<Tier> TIERS = List.of(
            new Tier("budget", "가성비", "가성비형"),
            new Tier("balanced", "균형", "균형형"),
            new Tier("performance", "성능", "고성능형")
    );
    private static final Map<String, String> CATEGORY_LABELS = Map.of(
            "CPU", "CPU",
            "MOTHERBOARD", "메인보드",
            "RAM", "RAM",
            "GPU", "GPU",
            "STORAGE", "SSD",
            "PSU", "파워",
            "CASE", "케이스",
            "COOLER", "쿨러"
    );
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;
    private final AiChatEngine aiChatEngine;
    private final BuildChatCacheService buildChatCacheService;
    private final PartReplacementRanker partReplacementRanker;
    private final CandidateReranker candidateReranker;
    private final PartRouteResolver partRouteResolver;
    private final BuildChatIntentRouter intentRouter;
    private final BuildChatSemanticCacheService semanticCacheService;

    @Autowired
    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker,
            CandidateReranker candidateReranker,
            PartRouteResolver partRouteResolver,
            BuildChatIntentRouter intentRouter,
            BuildChatSemanticCacheService semanticCacheService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
        this.aiChatEngine = aiChatEngine;
        this.buildChatCacheService = buildChatCacheService;
        this.partReplacementRanker = partReplacementRanker;
        this.candidateReranker = candidateReranker;
        this.partRouteResolver = partRouteResolver;
        this.intentRouter = intentRouter;
        this.semanticCacheService = semanticCacheService;
    }

    public BuildChatService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService, AiChatEngine aiChatEngine, BuildChatCacheService buildChatCacheService) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, null, new NoopCandidateReranker(), new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker
    ) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, partReplacementRanker, new NoopCandidateReranker(), new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            CandidateReranker candidateReranker
    ) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, null, candidateReranker, new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    public BuildChatService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            AiChatEngine aiChatEngine,
            BuildChatCacheService buildChatCacheService,
            PartReplacementRanker partReplacementRanker,
            CandidateReranker candidateReranker
    ) {
        this(jdbcTemplate, toolCheckService, aiChatEngine, buildChatCacheService, partReplacementRanker, candidateReranker, new PartRouteResolver(jdbcTemplate), new BuildChatIntentRouter(), BuildChatSemanticCacheService.disabled());
    }

    private BuildChatTierSnapshotStore tierSnapshotStore;

    @Value("${ai.build-chat.tier-snapshot.tolerance-pct:15}")
    private double tierSnapshotTolerancePct = 15;

    @Autowired(required = false)
    public void setTierSnapshotStore(BuildChatTierSnapshotStore tierSnapshotStore) {
        this.tierSnapshotStore = tierSnapshotStore;
    }

    public Map<String, Object> chat(Map<String, Object> request) {
        return chat(request, (String) null);
    }

    public Map<String, Object> chat(Map<String, Object> request, String requestedAiProfile) {
        return chat(request, requestedAiProfile, null);
    }

    public Map<String, Object> chat(Map<String, Object> request, CurrentUserService.CurrentUser user) {
        return chat(request, null, user);
    }

    public Map<String, Object> chat(Map<String, Object> request, String requestedAiProfile, CurrentUserService.CurrentUser user) {
        long startedNanos = System.nanoTime();
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = requireText(body.get("message"), "message는 필수입니다.");
        Long userId = user == null ? null : user.internalId();
        BudgetIntent rawBudgetIntent = budgetIntent(message);
        BuildChatIntentDecision intentDecision = intentRouter.decide(body, message);
        log.debug(
                "Build Chat intent decision: intent={}, confidence={}, sideEffectRisk={}, preferredPath={}, cachePolicy={}, ambiguityReasons={}",
                intentDecision.intent(),
                intentDecision.confidence(),
                intentDecision.sideEffectRisk(),
                intentDecision.preferredPath(),
                intentDecision.cachePolicy(),
                intentDecision.ambiguityReasons()
        );
        log.debug(
                "Build Chat request received: userId={}, requestedAiProfile={}, cacheLookup=true, cacheService={}",
                userId,
                requestedAiProfile,
                buildChatCacheService.getClass().getName()
        );
        if (intentDecision.intent() == BuildChatIntent.SIMULATE_REPLACEMENT) {
            Map<String, Object> response = performanceSimulationResponse(body, message)
                    .orElseGet(() -> simulationClarificationResponse(body, message));
            logBuildChatPath("FAST_SIMULATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.ASK_CLARIFICATION) {
            Map<String, Object> response = fastResponse(
                    "GENERAL",
                    "추천 기준을 조금 더 정하면 더 정확합니다. 예산, 해상도, 주 사용 게임이나 작업을 알려주세요.",
                    intentDecision.ambiguityReasons()
            );
            logBuildChatPath("FAST_CLARIFICATION", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        if (intentDecision.intent() == BuildChatIntent.UNSUPPORTED) {
            Map<String, Object> response = fastResponse(
                    "GENERAL",
                    "이 어시스턴트는 예산 견적 추천, 현재 견적 완성, 부품 교체 성능 비교를 도와드립니다. "
                            + "부품 탐색이나 견적 담기/빼기는 셀프 견적 그래프에서 직접 할 수 있어요. "
                            + "예: \"200만원 게이밍 PC 추천\", \"CPU를 9700X로 바꾸면?\"",
                    List.of("UNSUPPORTED_INTENT")
            );
            logBuildChatPath("FAST_UNSUPPORTED", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty());
            return response;
        }
        long stageStartNanos = System.nanoTime();
        var cachedResponse = buildChatCacheService.lookup(body, requestedAiProfile, userId);
        long redisMs = elapsedMs(stageStartNanos);
        if (cachedResponse.isPresent()) {
            Map<String, Object> response = cachedResponse.get();
            logBuildChatPath("CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs);
            return response;
        }
        if (tierSnapshotStore != null
                && rawBudgetIntent.hasBudget()
                && !rawBudgetIntent.explicitHardConstraint()
                && objectMaps(objectMap(body.get("currentQuoteDraft")).get("items")).isEmpty()) {
            stageStartNanos = System.nanoTime();
            Optional<BuildChatTierSnapshotStore.TierSnapshot> tierSnapshot =
                    tierSnapshotStore.bestFor(rawBudgetIntent.budget(), rawBudgetIntent.mode(), tierSnapshotTolerancePct);
            long tierMs = elapsedMs(stageStartNanos);
            if (tierSnapshot.isPresent()) {
                BuildChatTierSnapshotStore.TierSnapshot snapshot = tierSnapshot.get();
                Map<String, Object> response = fastResponse(
                        "BUDGET",
                        "내부 자산과 Tool 검증 기준으로 미리 계산한 추천 조합을 바로 가져왔습니다.",
                        snapshot.builds(),
                        snapshot.warnings()
                );
                buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
                logBuildChatPath("FAST_TIER_SNAPSHOT", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.empty(),
                        "redisMs=" + redisMs + " tierMs=" + tierMs + " tierBudgetWon=" + snapshot.tierBudgetWon());
                return response;
            }
        }
        stageStartNanos = System.nanoTime();
        Optional<Map<String, Object>> deterministicResponse = deterministicFastResponse(body, message, rawBudgetIntent);
        long deterministicMs = elapsedMs(stageStartNanos);
        if (deterministicResponse.isPresent()) {
            Map<String, Object> response = deterministicResponse.get();
            buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
            semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
            logBuildChatPath("FAST_DETERMINISTIC", startedNanos, userId, requestedAiProfile, false, BuildChatGuardStats.routeFallback(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        var semanticCachedResponse = semanticCacheService.lookup(body, requestedAiProfile, intentDecision);
        long semanticMs = elapsedMs(stageStartNanos);
        if (semanticCachedResponse.isPresent()) {
            Map<String, Object> response = semanticCachedResponse.get();
            logBuildChatPath("SEMANTIC_CACHE_HIT", startedNanos, userId, requestedAiProfile, true, BuildChatGuardStats.empty(),
                    "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs);
            return response;
        }
        stageStartNanos = System.nanoTime();
        AiChatEngineResponse engineResponse = aiChatEngine.respondLlmRequired(new AiChatEngineRequest(
                message,
                "HOME",
                firstText(text(body.get("selectedCategory")), detectPartCategory(message)),
                text(body.get("buildId")),
                text(body.get("draftId")),
                body,
                userId
        ), requestedAiProfile);
        long engineMs = elapsedMs(stageStartNanos);
        BuildChatGuardStats guardStats = new BuildChatGuardStats();
        Map<String, Object> response = responseMap(engineResponse, rawBudgetIntent, guardStats);
        candidateReranker.recordShadowScores(body, response, userId, requestedAiProfile);
        log.debug("Build Chat response generated: userId={}, requestedAiProfile={}, cacheStore=true", userId, requestedAiProfile);
        buildChatCacheService.storeAsync(body, requestedAiProfile, userId, response);
        semanticCacheService.storeAsync(body, requestedAiProfile, intentDecision, response);
        logBuildChatPath("LLM_FULL", startedNanos, userId, requestedAiProfile, false, guardStats,
                "redisMs=" + redisMs + " deterministicMs=" + deterministicMs + " semanticMs=" + semanticMs + " engineMs=" + engineMs);
        return response;
    }

    private static long elapsedMs(long startNanos) {
        return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
    }

    public record TierBuilds(List<Map<String, Object>> builds, List<String> warnings) {
    }

    // 예산 티어 스냅샷용 계산: 요청 경로의 budget fallback 탐색을 그대로 재사용한다 (MAX 모드 → 총액 ≤ 티어 보장)
    public TierBuilds computeBudgetTierBuilds(int budgetWon) {
        AiChatEngineResponse engineResponse = new AiChatEngineResponse(
                "내부 자산과 Tool 검증 기준으로 바로 추천 조합을 구성했습니다.",
                AiChatIntent.FULL_BUILD_RECOMMEND,
                List.of(),
                List.of(),
                List.of(),
                Map.of(),
                List.of(),
                List.of(),
                null
        );
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> builds = budgetFallbackBuilds(
                engineResponse,
                new BudgetIntent(budgetWon, "MAX", false),
                warnings,
                new BuildChatGuardStats()
        );
        return new TierBuilds(builds, warnings);
    }

    static Integer parseBudgetWon(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.replace(",", "").toLowerCase(Locale.ROOT);
        Matcher baekManWonMatcher = BUDGET_BAEKMANWON.matcher(normalized);
        if (baekManWonMatcher.find()) {
            return (int) Math.round(Double.parseDouble(baekManWonMatcher.group(1)) * 1_000_000);
        }
        Matcher manWonMatcher = BUDGET_MANWON.matcher(normalized);
        if (manWonMatcher.find()) {
            return (int) Math.round(Double.parseDouble(manWonMatcher.group(1)) * 10_000);
        }
        Matcher wonMatcher = BUDGET_WON.matcher(normalized);
        if (wonMatcher.find()) {
            return Integer.parseInt(wonMatcher.group(1));
        }
        return null;
    }

    static BudgetIntent budgetIntent(String message) {
        Integer budget = parseBudgetWon(message);
        if (budget == null || budget <= 0) {
            return BudgetIntent.empty();
        }
        String normalized = normalizeCommand(message);
        String mode;
        if (containsAnyNormalized(normalized, "이하", "안으로", "안쪽", "넘지않", "넘지말", "내로", "아래", "까지")) {
            mode = "MAX";
        } else if (containsAnyNormalized(normalized, "이상", "최소", "부터", "넘게")) {
            mode = "MIN";
        } else {
            mode = "TARGET";
        }
        return new BudgetIntent(budget, mode, hasRawHardPartConstraint(message));
    }

    private static boolean isStandaloneBuildRecommend(String message, Map<String, Object> request, BudgetIntent rawBudgetIntent) {
        if (!objectMap(request.get("currentQuoteDraft")).isEmpty() || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint())) {
            return false;
        }
        String normalized = normalizeCommand(message);
        boolean preferenceBuildLike = containsAnyNormalized(normalized, "위주", "말고", "cuda", "로컬ai", "실험용", "그래픽카드로추천");
        boolean buildLike = containsAnyNormalized(normalized,
                "pc", "컴퓨터", "견적", "본체", "구성",
                "목표", "게임", "게이밍", "qhd", "fhd", "4k", "hz", "배그", "발로란트", "오버워치", "사이버펑크", "로스트아크")
                || preferenceBuildLike;
        boolean recommendLike = containsAnyNormalized(normalized, "추천", "맞춰", "구성", "용pc", "pc");
        boolean budgetUseCaseLike = rawBudgetIntent != null
                && rawBudgetIntent.hasBudget()
                && hasSpecificBuildSignal(message, normalized);
        boolean mutationLike = containsAnyNormalized(normalized,
                "바꿔", "교체", "담아", "넣어", "빼", "삭제", "상세", "이동", "열어", "알림");
        return !mutationLike && hasSpecificBuildSignal(message, normalized) && (buildLike && recommendLike || budgetUseCaseLike);
    }

    private static boolean hasSpecificBuildSignal(String message, String normalized) {
        return parseBudgetWon(message) != null
                || containsAnyNormalized(normalized,
                "게임", "게이밍", "qhd", "4k", "144", "배그", "발로란트", "오버워치", "사이버펑크",
                "영상", "편집", "프리미어", "블렌더", "개발", "ai", "cuda", "로컬ai", "실험용",
                "엔비디아", "라데온", "nvidia", "고성능", "최고급", "끝판왕", "저소음", "조용",
                "작은", "컴팩트", "저장", "로딩", "사무", "학습", "흰색", "화이트", "업그레이드",
                "가성비", "입문", "보급", "균형");
    }

    static String detectPartCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<CategoryKeywords> checks = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "보드", "motherboard")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("ssd", "스토리지", "저장장치", "저장 공간", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("gpu", "그래픽카드", "그래픽 카드", "그래픽", "vga", "rtx", "cuda", "nvidia", "엔비디아", "geforce", "지포스")),
                new CategoryKeywords("CPU", List.of("cpu", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        return checks.stream()
                .filter(check -> check.keywords().stream().anyMatch(normalized::contains))
                .map(CategoryKeywords::category)
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> responseMap(
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            BuildChatGuardStats guardStats
    ) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> builds = engineBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
        warnings.addAll(buildWarnings(builds));
        warnings.addAll(stringList(engineResponse.parsedContext().get("warnings")));
        return MockData.map(
                "answerType", answerType(engineResponse.intent()),
                "message", engineResponse.assistantMessage(),
                "builds", builds,
                "warnings", distinct(warnings),
                "evidenceIds", engineResponse.evidenceIds(),
                "agentSessionId", engineResponse.agentSessionId()
        );
    }

    private Optional<Map<String, Object>> performanceSimulationResponse(Map<String, Object> request, String message) {
        if (!isPerformanceSimulationIntent(message)) {
            return Optional.empty();
        }
        String category = firstText(detectPartCategory(message), EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message).find() ? "GPU" : null);
        if (category == null) {
            return Optional.empty();
        }
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        if (draftItems.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Object> currentItem = findDraftItem(draftItems, category, message);
        if (currentItem.isEmpty()) {
            return Optional.empty();
        }
        PartCandidate currentPart = draftPartCandidate(currentItem);
        PartCandidate currentCpu = draftPartCandidate(findDraftItem(draftItems, "CPU", message));
        Optional<PartCandidate> targetPart = simulationTargetPart(category, message);
        if (targetPart.isEmpty()) {
            return Optional.of(fastResponse(
                    "GENERAL",
                    categoryLabel(category) + " 시뮬레이션을 하려면 바꿀 제품을 조금 더 구체적으로 알려주세요.",
                    List.of("SIMULATION_TARGET_NOT_FOUND")
            ));
        }

        PartCandidate target = targetPart.get();
        List<String> warnings = new ArrayList<>();
        List<PartCandidate> previewParts = simulationPreviewParts(draftItems, target);
        List<Map<String, Object>> toolResults = toolResults(previewParts, totalPrice(previewParts), warnings);
        boolean hasBlockingFail = hasBlockingToolFailure(toolResults);
        if (hasBlockingFail) {
            warnings.add("현재 견적 기준 호환성 문제가 있을 수 있어 실제 교체 전 케이스/파워/소켓 조건을 확인해야 합니다.");
        }

        BenchmarkSnapshot currentBenchmark = latestBenchmark(currentPart).orElse(null);
        BenchmarkSnapshot targetBenchmark = latestBenchmark(target).orElse(null);
        List<Map<String, Object>> currentFps = "GPU".equals(category) ? simulationFpsEvidence(currentCpu, currentPart, message) : List.of();
        List<Map<String, Object>> targetFps = "GPU".equals(category) ? simulationFpsEvidence(currentCpu, target, message) : List.of();
        Map<String, Object> simulation = simulationPayload(category, currentPart, target, currentBenchmark, targetBenchmark, currentFps, targetFps, warnings);
        Map<String, Object> response = fastResponse(
                "GENERAL",
                simulationSummary(category, currentPart, target, !simulationFpsComparisons(currentFps, targetFps).isEmpty()),
                warnings
        );
        response.put("simulation", simulation);

        return Optional.of(response);
    }

    private Map<String, Object> simulationClarificationResponse(Map<String, Object> request, String message) {
        Map<String, Object> currentQuoteDraft = objectMap(request.get("currentQuoteDraft"));
        List<Map<String, Object>> draftItems = objectMaps(currentQuoteDraft.get("items"));
        String category = firstText(detectPartCategory(message), EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message).find() ? "GPU" : null);
        String guidance;
        if (draftItems.isEmpty()) {
            guidance = "성능 비교는 현재 견적 기준으로 계산합니다. 먼저 셀프 견적 그래프에서 부품을 담거나 견적 추천을 받아주세요.";
        } else if (category == null) {
            guidance = "어떤 부품을 바꿀지 알려주세요. 예: \"CPU를 9700X로 바꾸면?\", \"그래픽카드를 5090으로 바꾸면?\"";
        } else {
            guidance = categoryLabel(category) + " 시뮬레이션을 하려면 바꿀 제품을 조금 더 구체적으로 알려주세요.";
        }
        return fastResponse("GENERAL", guidance, List.of("SIMULATION_TARGET_NOT_FOUND"));
    }

    private static boolean isPerformanceSimulationIntent(String message) {
        String normalized = normalizeCommand(message);
        boolean changeHypothesis = containsAnyNormalized(normalized, "바꾸면", "교체하면", "넣으면", "달면", "변경하면", "업그레이드하면", "으로가면", "로가면");
        boolean explicitImpactQuestion = containsAnyNormalized(normalized, "프레임", "fps", "성능", "벤치", "얼마나", "어떻게되", "어떻게", "어떨", "차이", "향상");
        boolean shortWhatIfQuestion = changeHypothesis && (normalized.endsWith("?") || normalized.endsWith("면") || containsAnyNormalized(normalized, "좋을까", "나을까"));
        return changeHypothesis && (explicitImpactQuestion || shortWhatIfQuestion);
    }

    private Optional<PartCandidate> simulationTargetPart(String category, String message) {
        if (category == null) {
            return Optional.empty();
        }
        String gpuClass = "GPU".equals(category) ? targetGpuClass(message) : null;
        String modelToken = simulationModelToken(category, message);
        Integer capacityGb = parseCapacityGb(message);
        Integer wattage = parseWattage(message);

        List<String> filters = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        params.add(category);
        if (gpuClass != null) {
            filters.add("p.attributes->>'gpuClass' = ?");
            params.add(gpuClass);
        }
        if (modelToken != null) {
            filters.add("(upper(p.name) LIKE '%' || upper(?) || '%' OR upper(coalesce(p.manufacturer, '')) LIKE '%' || upper(?) || '%')");
            params.add(modelToken);
            params.add(modelToken);
        }
        if (capacityGb != null && ("RAM".equals(category) || "STORAGE".equals(category))) {
            filters.add(safeNumericAttribute("capacityGb", "kitCapacityGb", "memoryGb") + " >= ?");
            params.add(capacityGb);
        }
        if (wattage != null && "PSU".equals(category)) {
            filters.add(safeNumericAttribute("capacityW", "wattage") + " >= ?");
            params.add(wattage);
        }

        if (filters.isEmpty()) {
            return partRecommendations(category, 1).stream()
                    .findFirst()
                    .map(this::partCandidate);
        }

        List<String> order = new ArrayList<>();
        if (gpuClass != null) {
            order.add("CASE WHEN p.attributes->>'gpuClass' = '" + gpuClass.replace("'", "''") + "' THEN 0 ELSE 1 END");
        }
        if (modelToken != null) {
            order.add("CASE WHEN upper(p.name) LIKE '%' || upper('" + modelToken.replace("'", "''") + "') || '%' THEN 0 ELSE 1 END");
        }
        if (capacityGb != null && ("RAM".equals(category) || "STORAGE".equals(category))) {
            order.add(safeNumericAttribute("capacityGb", "kitCapacityGb", "memoryGb") + " ASC");
        }
        if (wattage != null && "PSU".equals(category)) {
            order.add(safeNumericAttribute("capacityW", "wattage") + " ASC");
        }
        order.add("b.score DESC NULLS LAST");
        order.add("p.price ASC");
        order.add("p.name ASC");

        String sql = """
                        SELECT p.id AS internal_id,
                               p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.attributes,
                               b.score AS benchmark_score
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT score
                          FROM benchmark_summaries bs
                          WHERE bs.part_id = p.id
                            AND bs.deleted_at IS NULL
                          ORDER BY bs.score DESC NULLS LAST, bs.created_at DESC
                          LIMIT 1
                        ) b ON true
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                          AND p.price IS NOT NULL
                          AND (""" + String.join(" OR ", filters) + """
                          )
                        """ + "\nORDER BY " + String.join(", ", order) + "\nLIMIT 1\n";

        return jdbcTemplate.queryForList(sql, params.toArray())
                .stream()
                .findFirst()
                .map(this::partCandidate);
    }

    private static String targetGpuClass(String message) {
        Matcher matcher = EXPLICIT_GPU_MODEL.matcher(message == null ? "" : message);
        if (!matcher.find()) {
            return null;
        }
        String model = matcher.group(1);
        String suffix = matcher.group(2);
        String result = "RTX_" + model;
        if (suffix != null && !suffix.isBlank()) {
            result += "_" + suffix.toUpperCase(Locale.ROOT);
        }
        return result;
    }

    private static String simulationModelToken(String category, String message) {
        String text = message == null ? "" : message;
        if ("GPU".equals(category)) {
            String gpuClass = targetGpuClass(message);
            return gpuClass == null ? null : gpuClass.replace("RTX_", "RTX ").replace("_", " ");
        }
        if ("CPU".equals(category)) {
            Matcher matcher = EXPLICIT_CPU_MODEL.matcher(text);
            return matcher.find() ? matcher.group() : null;
        }
        Matcher model = Pattern.compile("(?i)([a-z가-힣]*\\s*\\d{2,5}[a-z0-9가-힣-]*)").matcher(text);
        if (model.find()) {
            return model.group(1).trim();
        }
        String brand = brandToken(text);
        return brand == null ? null : brand;
    }

    private static String safeNumericAttribute(String... keys) {
        String coalesce = java.util.Arrays.stream(keys)
                .map(key -> "p.attributes->>'" + key + "'")
                .collect(java.util.stream.Collectors.joining(", "));
        return "coalesce(NULLIF(regexp_replace(coalesce(" + coalesce + ", '0'), '[^0-9]', '', 'g'), '')::int, 0)";
    }

    private PartCandidate draftPartCandidate(Map<String, Object> item) {
        String publicId = text(item.get("partId"));
        if (publicId != null && isUuid(publicId)) {
            try {
                return partByPublicId(publicId);
            } catch (RuntimeException ignored) {
                // Draft data is still usable for lightweight simulation if the catalog row was removed.
            }
        }
        PartCandidate candidate = partCandidateFromDraftItem(item);
        if (candidate == null) {
            return new PartCandidate(null, null, text(item.get("category")), firstText(text(item.get("name")), "부품"), text(item.get("manufacturer")), 0, objectMap(item.get("attributes")));
        }
        return candidate;
    }

    private List<PartCandidate> simulationPreviewParts(List<Map<String, Object>> draftItems, PartCandidate target) {
        List<PartCandidate> result = new ArrayList<>();
        boolean replaced = false;
        for (Map<String, Object> item : draftItems) {
            String category = text(item.get("category"));
            if (target.category().equals(category)) {
                if (!replaced) {
                    result.add(target);
                    replaced = true;
                }
                continue;
            }
            PartCandidate candidate = draftPartCandidate(item);
            if (candidate != null) {
                result.add(candidate);
            }
        }
        if (!replaced) {
            result.add(target);
        }
        return result;
    }

    private Optional<BenchmarkSnapshot> latestBenchmark(PartCandidate part) {
        if (part == null || part.internalId() == null) {
            return Optional.empty();
        }
        return jdbcTemplate.queryForList("""
                        SELECT score,
                               summary
                        FROM benchmark_summaries
                        WHERE part_id = ?
                          AND deleted_at IS NULL
                        ORDER BY score DESC NULLS LAST, created_at DESC, id DESC
                        LIMIT 1
                        """, part.internalId())
                .stream()
                .findFirst()
                .map(row -> new BenchmarkSnapshot(doubleValue(row.get("score")), text(row.get("summary"))));
    }

    private List<Map<String, Object>> simulationFpsEvidence(PartCandidate cpu, PartCandidate gpu, String message) {
        if (gpu == null) {
            return List.of();
        }
        String gpuClass = hardwareClass(gpu);
        if (gpuClass == null) {
            return List.of();
        }
        String cpuClass = hardwareClass(cpu);
        Long gpuId = gpu.internalId() == null ? -1L : gpu.internalId();
        Long cpuId = cpu == null || cpu.internalId() == null ? -1L : cpu.internalId();
        String gameKey = gameKeyFromText(message);
        String resolution = resolutionFromText(message);
        List<Object> params = new ArrayList<>();
        params.add(gpuId);
        params.add(gpuClass);
        params.add(cpuId);
        params.add(cpuClass);
        String resolutionRank = "0 AS resolution_rank\n";
        if (resolution != null) {
            resolutionRank = "CASE WHEN resolution = ? THEN 0 ELSE 1 END AS resolution_rank\n";
            params.add(resolution);
        }
        params.add(gpuId);
        params.add(gpuClass);
        String gameFilter = "";
        if (gameKey != null) {
            gameFilter = " AND game_key = ?\n";
            params.add(gameKey);
        }
        params.add(4);
        return jdbcTemplate.queryForList("""
                        SELECT game_title,
                               game_key,
                               resolution,
                               graphics_preset,
                               avg_fps,
                               one_percent_low_fps,
                               source_name,
                               confidence,
                               metadata,
                               CASE WHEN gpu_part_id = ? THEN 0 WHEN metadata->>'gpuClass' = ? THEN 1 ELSE 2 END AS gpu_rank,
                               CASE WHEN cpu_part_id = ? THEN 0 WHEN metadata->>'cpuClass' = ? THEN 1 ELSE 2 END AS cpu_rank,
                               """ + resolutionRank + """
                        FROM game_fps_benchmarks
                        WHERE deleted_at IS NULL
                          AND (gpu_part_id = ? OR metadata->>'gpuClass' = ?)
                        """ + gameFilter + """
                        ORDER BY gpu_rank,
                                 cpu_rank,
                                 resolution_rank,
                                 CASE confidence WHEN 'HIGH' THEN 0 WHEN 'MEDIUM' THEN 1 ELSE 2 END,
                                 source_checked_at DESC,
                                 id DESC
                        LIMIT ?
                        """, params.toArray());
    }

    private Map<String, Object> simulationPayload(
            String category,
            PartCandidate currentPart,
            PartCandidate targetPart,
            BenchmarkSnapshot currentBenchmark,
            BenchmarkSnapshot targetBenchmark,
            List<Map<String, Object>> currentFps,
            List<Map<String, Object>> targetFps,
            List<String> warnings
    ) {
        List<Map<String, Object>> fpsComparisons = simulationFpsComparisons(currentFps, targetFps);
        List<Map<String, Object>> specComparisons = simulationSpecComparisons(category, currentPart, targetPart);
        List<String> simulationWarnings = new ArrayList<>(warnings);
        if (fpsComparisons.isEmpty() && "GPU".equals(category)) {
            simulationWarnings.add("요청 조건과 정확히 맞는 게임 FPS 자료가 부족해 확인 가능한 벤치마크와 스펙 중심으로 표시했습니다.");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", "PERFORMANCE_COMPARISON");
        result.put("category", category);
        result.put("currentPart", simulationPart(currentPart));
        result.put("targetPart", simulationPart(targetPart));
        result.put("summary", simulationSummary(category, currentPart, targetPart, !fpsComparisons.isEmpty()));
        result.put("scoreComparison", simulationScoreComparison(currentBenchmark, targetBenchmark));
        result.put("fpsComparisons", fpsComparisons);
        result.put("specComparisons", specComparisons);
        result.put("warnings", distinct(simulationWarnings));
        result.put("disclaimer", "실제 FPS는 게임 버전, 옵션, 드라이버, 냉각 상태에 따라 달라질 수 있습니다.");
        return result;
    }

    private static Map<String, Object> simulationPart(PartCandidate part) {
        return MockData.map(
                "partId", part.publicId(),
                "category", part.category(),
                "name", part.name(),
                "manufacturer", part.manufacturer(),
                "price", part.price()
        );
    }

    private static Map<String, Object> simulationScoreComparison(BenchmarkSnapshot currentBenchmark, BenchmarkSnapshot targetBenchmark) {
        Double current = currentBenchmark == null ? null : currentBenchmark.score();
        Double target = targetBenchmark == null ? null : targetBenchmark.score();
        if (current == null && target == null) {
            return null;
        }
        return MockData.map(
                "label", "벤치마크 기반 점수",
                "currentScore", current,
                "targetScore", target,
                "delta", current == null || target == null ? null : target - current
        );
    }

    private static String simulationSummary(String category, PartCandidate currentPart, PartCandidate targetPart, boolean hasFpsRows) {
        if ("GPU".equals(category) && hasFpsRows) {
            return currentPart.name() + "에서 " + targetPart.name() + "(으)로 바꾸면 게임 FPS가 해상도별로 달라질 수 있습니다. 아래 벤치마크 표를 참고하세요.";
        }
        return categoryLabel(category) + "를 " + targetPart.name() + "(으)로 바꿨을 때 확인 가능한 벤치마크와 주요 스펙 차이를 정리했습니다.";
    }

    private List<Map<String, Object>> simulationFpsComparisons(List<Map<String, Object>> currentFps, List<Map<String, Object>> targetFps) {
        if (targetFps == null || targetFps.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> target : targetFps.stream().limit(3).toList()) {
            String key = text(target.get("game_key")) + "|" + text(target.get("resolution"));
            Map<String, Object> current = currentFps == null ? Map.of() : currentFps.stream()
                    .filter(item -> key.equals(text(item.get("game_key")) + "|" + text(item.get("resolution"))))
                    .findFirst()
                    .orElse(Map.of());
            Double targetAvg = doubleValue(target.get("avg_fps"));
            Double currentAvg = doubleValue(current.get("avg_fps"));
            rows.add(MockData.map(
                    "gameTitle", firstText(text(target.get("game_title")), "게임"),
                    "resolution", firstText(text(target.get("resolution")), "해상도 미상"),
                    "graphicsPreset", text(target.get("graphics_preset")),
                    "currentFps", currentAvg,
                    "targetFps", targetAvg,
                    "deltaFps", currentAvg == null || targetAvg == null ? null : targetAvg - currentAvg,
                    "source", text(target.get("source_name"))
            ));
        }
        return rows;
    }

    private static List<Map<String, Object>> simulationSpecComparisons(String category, PartCandidate currentPart, PartCandidate targetPart) {
        List<Map<String, Object>> rows = new ArrayList<>();
        switch (category) {
            case "GPU" -> {
                addSpecNumber(rows, "VRAM", currentPart, targetPart, "GB", "vramGb", "memoryGb");
                addSpecNumber(rows, "소비전력", currentPart, targetPart, "W", "wattage", "tdpW");
                addSpecNumber(rows, "그래픽카드 길이", currentPart, targetPart, "mm", "lengthMm");
            }
            case "CPU" -> {
                addSpecNumber(rows, "코어", currentPart, targetPart, "개", "coreCount", "cores");
                addSpecNumber(rows, "스레드", currentPart, targetPart, "개", "threadCount", "threads");
                addSpecNumber(rows, "TDP", currentPart, targetPart, "W", "tdpW", "wattage");
            }
            case "RAM" -> {
                addSpecNumber(rows, "총 용량", currentPart, targetPart, "GB", "capacityGb", "kitCapacityGb", "memoryGb");
                addSpecNumber(rows, "모듈 수", currentPart, targetPart, "개", "moduleCount");
                addSpecNumber(rows, "메모리 속도", currentPart, targetPart, "MHz", "speedMhz");
                addSpecText(rows, "메모리 세대", currentPart, targetPart, "memoryType", "ddrGeneration");
            }
            case "STORAGE" -> {
                addSpecNumber(rows, "용량", currentPart, targetPart, "GB", "capacityGb");
                addSpecText(rows, "인터페이스", currentPart, targetPart, "interface", "formFactor");
                addSpecNumber(rows, "순차 읽기", currentPart, targetPart, "MB/s", "readMbps");
                addSpecNumber(rows, "순차 쓰기", currentPart, targetPart, "MB/s", "writeMbps");
            }
            case "PSU" -> {
                addSpecNumber(rows, "정격 출력", currentPart, targetPart, "W", "capacityW", "wattage");
                addSpecText(rows, "효율 등급", currentPart, targetPart, "efficiency");
                addSpecText(rows, "ATX 규격", currentPart, targetPart, "atxSpec", "pcieSpec");
                addSpecText(rows, "GPU 커넥터", currentPart, targetPart, "gpuConnector", "powerConnector");
            }
            case "MOTHERBOARD" -> {
                addSpecText(rows, "칩셋", currentPart, targetPart, "chipset");
                addSpecText(rows, "메모리 규격", currentPart, targetPart, "memoryType");
                addSpecText(rows, "폼팩터", currentPart, targetPart, "formFactor");
                addSpecText(rows, "Wi-Fi", currentPart, targetPart, "hasWifi");
            }
            case "CASE" -> {
                addSpecNumber(rows, "GPU 장착 길이", currentPart, targetPart, "mm", "maxGpuLengthMm");
                addSpecNumber(rows, "CPU 쿨러 높이", currentPart, targetPart, "mm", "maxCpuCoolerHeightMm");
                addSpecNumber(rows, "PSU 길이", currentPart, targetPart, "mm", "maxPsuLengthMm");
                addSpecText(rows, "전면 메쉬", currentPart, targetPart, "frontMesh", "airflowFocus");
            }
            case "COOLER" -> {
                addSpecText(rows, "냉각 방식", currentPart, targetPart, "coolerType");
                addSpecNumber(rows, "TDP 대응", currentPart, targetPart, "W", "tdpW");
                addSpecNumber(rows, "높이", currentPart, targetPart, "mm", "heightMm");
                addSpecNumber(rows, "라디에이터", currentPart, targetPart, "mm", "radiatorLengthMm");
            }
            default -> {
            }
        }
        return rows;
    }

    private static void addSpecNumber(List<Map<String, Object>> rows, String label, PartCandidate currentPart, PartCandidate targetPart, String unit, String... keys) {
        Integer current = firstAttributeNumber(currentPart, keys);
        Integer target = firstAttributeNumber(targetPart, keys);
        if (current == null && target == null) {
            return;
        }
        rows.add(MockData.map(
                "label", label,
                "currentValue", current == null ? null : current + unit,
                "targetValue", target == null ? null : target + unit,
                "deltaText", current == null || target == null ? null : signedNumber(target - current, unit)
        ));
    }

    private static void addSpecText(List<Map<String, Object>> rows, String label, PartCandidate currentPart, PartCandidate targetPart, String... keys) {
        String current = firstAttributeText(currentPart, keys);
        String target = firstAttributeText(targetPart, keys);
        if (current == null && target == null) {
            return;
        }
        rows.add(MockData.map(
                "label", label,
                "currentValue", current,
                "targetValue", target,
                "deltaText", null
        ));
    }

    private static Integer firstAttributeNumber(PartCandidate part, String... keys) {
        if (part == null || part.attributes() == null) {
            return null;
        }
        for (String key : keys) {
            Integer value = numberValue(part.attributes().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String firstAttributeText(PartCandidate part, String... keys) {
        if (part == null || part.attributes() == null) {
            return null;
        }
        for (String key : keys) {
            String value = text(part.attributes().get(key));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String signedNumber(int value, String unit) {
        if (value == 0) {
            return "변화 없음";
        }
        return (value > 0 ? "+" : "") + value + unit;
    }

    private static String hardwareClass(PartCandidate part) {
        if (part == null) {
            return null;
        }
        String explicit = firstText(text(part.attributes().get("hardwareClass")), firstText(text(part.attributes().get("gpuClass")), text(part.attributes().get("cpuClass"))));
        if (explicit != null) {
            return explicit;
        }
        String name = firstText(part.name(), "").toLowerCase(Locale.ROOT);
        if ("GPU".equals(part.category())) {
            if (name.contains("5090")) return "RTX_5090";
            if (name.contains("5080")) return "RTX_5080";
            if (name.matches(".*5070\\s*ti.*") || name.contains("5070ti")) return "RTX_5070_TI";
            if (name.contains("5070")) return "RTX_5070";
            if (name.matches(".*5060\\s*ti.*") || name.contains("5060ti")) return "RTX_5060_TI";
            if (name.contains("5060")) return "RTX_5060";
        }
        if ("CPU".equals(part.category())) {
            if (name.contains("9950x3d")) return "RYZEN_9_9950X3D";
            if (name.contains("9950x")) return "RYZEN_9_9950X";
            if (name.contains("9900x3d")) return "RYZEN_9_9900X3D";
            if (name.contains("9800x3d")) return "RYZEN_7_9800X3D";
            if (name.contains("9700x")) return "RYZEN_7_9700X";
            if (name.contains("9600x")) return "RYZEN_5_9600X";
        }
        return null;
    }

    private static String gameKeyFromText(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAnyText(text, "배그", "pubg", "battleground")) return "pubg";
        if (containsAnyText(text, "로아", "로스트아크", "lost ark")) return "lost-ark";
        if (containsAnyText(text, "발로란트", "valorant")) return "valorant";
        if (containsAnyText(text, "오버워치", "overwatch")) return "overwatch-2";
        if (containsAnyText(text, "사이버펑크", "사펑", "cyberpunk")) return "cyberpunk-2077";
        return null;
    }

    private static String resolutionFromText(String message) {
        String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (containsAnyText(text, "4k", "uhd", "2160")) return "4K";
        if (containsAnyText(text, "qhd", "1440", "2560")) return "QHD";
        if (containsAnyText(text, "fhd", "1080", "1920")) return "FHD";
        return null;
    }

    private static boolean containsAnyText(String value, String... needles) {
        if (value == null) {
            return false;
        }
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String brandToken(String message) {
        String normalized = normalizeCommand(message);
        for (String token : List.of(
                "msi", "asus", "gigabyte", "기가바이트", "리안리", "lianli", "corsair", "커세어",
                "samsung", "삼성", "superflower", "슈퍼플라워", "fsp", "arctic", "녹투아", "noctua"
        )) {
            if (normalized.contains(normalizeCommand(token))) {
                return token;
            }
        }
        return null;
    }

    private Map<String, Object> fastResponse(String answerType, String message, List<String> warnings) {
        return fastResponse(answerType, message, List.of(), warnings);
    }

    private Map<String, Object> fastResponse(
            String answerType,
            String message,
            List<Map<String, Object>> builds,
            List<String> warnings
    ) {
        return MockData.map(
                "answerType", answerType,
                "message", message,
                "builds", builds == null ? List.of() : builds,
                "warnings", distinct(warnings),
                "evidenceIds", List.of(),
                "agentSessionId", null
        );
    }

    private Optional<Map<String, Object>> deterministicFastResponse(Map<String, Object> request, String message, BudgetIntent rawBudgetIntent) {
        if (isStandaloneBuildRecommend(message, request, rawBudgetIntent)) {
            AiChatEngineResponse engineResponse = new AiChatEngineResponse(
                    "내부 자산과 Tool 검증 기준으로 바로 추천 조합을 구성했습니다.",
                    AiChatIntent.FULL_BUILD_RECOMMEND,
                    List.of(),
                    List.of(),
                    List.of(),
                    Map.of(),
                    List.of(),
                    List.of(),
                    null
            );
            List<String> warnings = new ArrayList<>();
            BuildChatGuardStats stats = new BuildChatGuardStats();
            List<Map<String, Object>> builds = rawBudgetIntent != null && rawBudgetIntent.hasBudget()
                    ? budgetFallbackBuilds(engineResponse, rawBudgetIntent, warnings, stats)
                    : openBudgetFallbackBuilds(warnings);
            if (!builds.isEmpty()) {
                return Optional.of(fastResponse(
                        "BUDGET",
                        "내부 자산과 Tool 검증 기준으로 추천 조합 3개를 바로 구성했습니다.",
                        builds,
                        warnings
                ));
            }
        }
        return Optional.empty();
    }

    private List<Map<String, Object>> openBudgetFallbackBuilds(List<String> warnings) {
        List<List<PartCandidate>> groups = new ArrayList<>();
        for (String category : fallbackCategories(true, true)) {
            List<PartCandidate> candidates = partRecommendations(category, 8).stream()
                    .map(this::partCandidate)
                    .toList();
            if (candidates.isEmpty()) {
                return List.of();
            }
            groups.add(candidates);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        LinkedHashSet<String> seenKeys = new LinkedHashSet<>();
        int maxCandidates = groups.stream().mapToInt(List::size).max().orElse(0);
        for (int offset = 0; offset < maxCandidates && result.size() < 3; offset += 1) {
            List<PartCandidate> parts = new ArrayList<>();
            for (List<PartCandidate> group : groups) {
                parts.add(group.get(Math.min(offset, group.size() - 1)));
            }
            String key = parts.stream().map(PartCandidate::publicId).toList().toString();
            if (!seenKeys.add(key)) {
                continue;
            }
            List<String> localWarnings = new ArrayList<>();
            List<Map<String, Object>> toolResults = toolResults(parts, totalPrice(parts), localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                continue;
            }
            localWarnings.addAll(toolWarnings(toolResults));
            Tier tier = TIERS.get(Math.min(result.size(), TIERS.size() - 1));
            result.add(openBudgetFallbackBuildMap(tier, parts, toolResults, localWarnings));
        }
        if (!result.isEmpty()) {
            warnings.add("빠른 응답을 위해 내부 자산과 Tool 검증 기준으로 추천 조합을 즉시 구성했습니다.");
        }
        return result;
    }

    private Map<String, Object> openBudgetFallbackBuildMap(
            Tier tier,
            List<PartCandidate> parts,
            List<Map<String, Object>> toolResults,
            List<String> warnings
    ) {
        int totalPrice = totalPrice(parts);
        List<Map<String, Object>> items = parts.stream()
                .map(part -> partItem(part, "내부 자산 빠른 추천"))
                .toList();
        return MockData.map(
                "id", "ai-engine-fast-open-budget-" + tier.id() + "-" + Math.abs(items.hashCode()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", tier.title() + " 빠른 추천 조합",
                "summary", "내부 ACTIVE 자산과 Tool 검증을 기준으로 빠르게 구성했습니다.",
                "recommendedFor", List.of("빠른 추천", "내부 자산", "Tool 검증"),
                "totalPrice", totalPrice,
                "badges", List.of(tier.title(), "OPEN_BUDGET", "FAST"),
                "budgetWon", totalPrice,
                "budgetLabel", "예산 미지정",
                "tierLabel", tier.title(),
                "appliedPartCategories", List.of(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings),
                "evidenceIds", List.of()
        );
    }

    private List<Map<String, Object>> engineBuilds(
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        List<AiChatEngineResponse.BuildRecommendation> recommendations = engineResponse.recommendations();
        if (recommendations == null || recommendations.isEmpty()) {
            return budgetFallbackBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (int index = 0; index < recommendations.size(); index += 1) {
            Map<String, Object> build = engineBuildMap(recommendations.get(index), index, engineResponse, rawBudgetIntent, warnings);
            if (hasBlockingToolFailure(objectMaps(build.get("toolResults")))) {
                guardStats.blockingFailDropped += 1;
                warnings.add("Tool 검증에서 장착/호환/전력 불가로 판정된 추천 조합을 제외했습니다.");
                continue;
            }
            if (!withinBudgetGuard(build, engineResponse.parsedContext(), rawBudgetIntent)) {
                guardStats.budgetGuardDropped += 1;
                warnings.add("명시 예산 범위를 벗어난 추천 조합을 제외했습니다.");
                continue;
            }
            result.add(build);
        }
        if (!result.isEmpty()) {
            return result;
        }
        return budgetFallbackBuilds(engineResponse, rawBudgetIntent, warnings, guardStats);
    }

    private List<Map<String, Object>> budgetFallbackBuilds(
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            List<String> warnings,
            BuildChatGuardStats guardStats
    ) {
        Map<String, Object> parsedContext = engineResponse.parsedContext() == null ? Map.of() : engineResponse.parsedContext();
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget() || hasEffectiveHardConstraint(parsedContext, rawBudgetIntent)) {
            return List.of();
        }

        int ramQuantity = rawBudgetIntent.budget() <= 1_200_000 ? 1 : 2;
        List<Map<String, Object>> result = new ArrayList<>();
        boolean preferLiteBuild = rawBudgetIntent.budget() <= 1_500_000;
        if (preferLiteBuild) {
            collectBudgetFallbackBuilds(false, false, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
            collectBudgetFallbackBuilds(true, true, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
        } else {
            collectBudgetFallbackBuilds(true, true, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
            collectBudgetFallbackBuilds(false, false, ramQuantity, rawBudgetIntent, engineResponse.evidenceIds(), result);
        }

        if (!result.isEmpty()) {
            warnings.add("명시 예산 범위에 맞춰 내부 자산 기준 보조 견적을 재구성했습니다.");
            if (guardStats != null) {
                guardStats.routeFallbackUsed = true;
            }
        }
        return result.stream().limit(3).toList();
    }

    private void collectBudgetFallbackBuilds(
            boolean includeGpu,
            boolean includeCooler,
            int ramQuantity,
            BudgetIntent rawBudgetIntent,
            List<String> evidenceIds,
            List<Map<String, Object>> result
    ) {
        if (result.size() >= 3) {
            return;
        }
        List<List<PartCandidate>> groups = new ArrayList<>();
        for (String category : fallbackCategories(includeGpu, includeCooler)) {
            List<PartCandidate> candidates = pricePartCandidates(category, fallbackCandidateLimit(category, includeGpu));
            if (candidates.isEmpty()) {
                return;
            }
            groups.add(candidates);
        }
        int lowerBound = budgetLowerBound(rawBudgetIntent);
        int upperBound = budgetUpperBound(rawBudgetIntent);
        int[] minRemaining = minRemainingPrices(groups, ramQuantity);
        int[] maxRemaining = maxRemainingPrices(groups, ramQuantity);
        collectBudgetFallbackCombination(
                groups,
                0,
                new ArrayList<>(),
                new LinkedHashSet<>(),
                0,
                lowerBound,
                upperBound,
                minRemaining,
                maxRemaining,
                ramQuantity,
                rawBudgetIntent,
                evidenceIds,
                result
        );
    }

    private void collectBudgetFallbackCombination(
            List<List<PartCandidate>> groups,
            int groupIndex,
            List<PartCandidate> selected,
            LinkedHashSet<String> seenKeys,
            int partialPrice,
            int lowerBound,
            int upperBound,
            int[] minRemaining,
            int[] maxRemaining,
            int ramQuantity,
            BudgetIntent rawBudgetIntent,
            List<String> evidenceIds,
            List<Map<String, Object>> result
    ) {
        if (result.size() >= 3) {
            return;
        }
        if (partialPrice + minRemaining[groupIndex] > upperBound) {
            return;
        }
        if (partialPrice + maxRemaining[groupIndex] < lowerBound) {
            return;
        }
        if (groupIndex >= groups.size()) {
            String key = selected.stream().map(PartCandidate::publicId).toList().toString();
            if (!seenKeys.add(key)) {
                return;
            }
            int totalPrice = partialPrice;
            if (!withinBudgetGuard(MockData.map("totalPrice", totalPrice), Map.of(), rawBudgetIntent)) {
                return;
            }
            List<String> localWarnings = new ArrayList<>();
            int toolBudget = "MAX".equals(rawBudgetIntent.mode()) ? rawBudgetIntent.budget() : totalPrice;
            List<Map<String, Object>> toolResults = toolResults(selected, toolBudget, localWarnings);
            if (hasBlockingToolFailure(toolResults)) {
                return;
            }
            localWarnings.addAll(toolWarnings(toolResults));
            Tier tier = TIERS.get(Math.min(result.size(), TIERS.size() - 1));
            result.add(budgetFallbackBuildMap(tier, selected, rawBudgetIntent, ramQuantity, toolResults, localWarnings, evidenceIds));
            return;
        }
        for (PartCandidate candidate : groups.get(groupIndex)) {
            int candidatePrice = effectiveCandidatePrice(candidate, ramQuantity);
            int nextPartialPrice = partialPrice + candidatePrice;
            if (nextPartialPrice + minRemaining[groupIndex + 1] > upperBound) {
                break;
            }
            if (nextPartialPrice + maxRemaining[groupIndex + 1] < lowerBound) {
                continue;
            }
            selected.add(candidate);
            collectBudgetFallbackCombination(
                    groups,
                    groupIndex + 1,
                    selected,
                    seenKeys,
                    nextPartialPrice,
                    lowerBound,
                    upperBound,
                    minRemaining,
                    maxRemaining,
                    ramQuantity,
                    rawBudgetIntent,
                    evidenceIds,
                    result
            );
            selected.remove(selected.size() - 1);
            if (result.size() >= 3) {
                return;
            }
        }
    }

    private static int budgetLowerBound(BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget()) {
            return 0;
        }
        if ("MIN".equals(rawBudgetIntent.mode())) {
            return rawBudgetIntent.budget();
        }
        if ("TARGET".equals(rawBudgetIntent.mode())) {
            return (int) Math.floor(rawBudgetIntent.budget() * 0.875);
        }
        return 0;
    }

    private static int budgetUpperBound(BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent == null || !rawBudgetIntent.hasBudget()) {
            return Integer.MAX_VALUE;
        }
        if ("MAX".equals(rawBudgetIntent.mode())) {
            return rawBudgetIntent.budget();
        }
        if ("TARGET".equals(rawBudgetIntent.mode())) {
            return (int) Math.ceil(rawBudgetIntent.budget() * 1.125);
        }
        return Integer.MAX_VALUE;
    }

    private static int[] minRemainingPrices(List<List<PartCandidate>> groups, int ramQuantity) {
        int[] remaining = new int[groups.size() + 1];
        remaining[groups.size()] = 0;
        for (int index = groups.size() - 1; index >= 0; index -= 1) {
            int min = groups.get(index).stream()
                    .mapToInt(candidate -> effectiveCandidatePrice(candidate, ramQuantity))
                    .min()
                    .orElse(0);
            remaining[index] = remaining[index + 1] + min;
        }
        return remaining;
    }

    private static int[] maxRemainingPrices(List<List<PartCandidate>> groups, int ramQuantity) {
        int[] remaining = new int[groups.size() + 1];
        remaining[groups.size()] = 0;
        for (int index = groups.size() - 1; index >= 0; index -= 1) {
            int max = groups.get(index).stream()
                    .mapToInt(candidate -> effectiveCandidatePrice(candidate, ramQuantity))
                    .max()
                    .orElse(0);
            remaining[index] = remaining[index + 1] + max;
        }
        return remaining;
    }

    private static int effectiveCandidatePrice(PartCandidate candidate, int ramQuantity) {
        int quantity = "RAM".equals(candidate.category()) ? Math.max(1, Math.min(4, ramQuantity)) : defaultQuantity(candidate.category());
        return Math.max(0, candidate.price() == null ? 0 : candidate.price()) * quantity;
    }

    private Map<String, Object> budgetFallbackBuildMap(
            Tier tier,
            List<PartCandidate> parts,
            BudgetIntent rawBudgetIntent,
            int ramQuantity,
            List<Map<String, Object>> toolResults,
            List<String> warnings,
            List<String> evidenceIds
    ) {
        int totalPrice = totalPrice(parts, ramQuantity);
        List<Map<String, Object>> items = parts.stream()
                .map(part -> partItem(part, "명시 예산 기준 내부 자산 보조 추천", quantityForBudgetFallback(part, ramQuantity)))
                .toList();
        return MockData.map(
                "id", "ai-engine-budget-fallback-" + tier.id() + "-" + Math.abs(items.hashCode()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", tier.title() + " 예산 맞춤 조합",
                "summary", "명시 예산 범위를 우선해 내부 ACTIVE 자산과 Tool 검증 기준으로 재구성했습니다.",
                "recommendedFor", List.of("명시 예산", "내부 자산", "Tool 검증"),
                "totalPrice", totalPrice,
                "badges", List.of(tier.title(), rawBudgetIntent.mode(), "BUDGET_GUARD"),
                "budgetWon", rawBudgetIntent.budget(),
                "budgetLabel", formatBudgetLabel(rawBudgetIntent.budget()),
                "tierLabel", tier.title(),
                "appliedPartCategories", List.of(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings),
                "evidenceIds", evidenceIds == null ? List.of() : evidenceIds
        );
    }

    private Map<String, Object> engineBuildMap(
            AiChatEngineResponse.BuildRecommendation recommendation,
            int index,
            AiChatEngineResponse engineResponse,
            BudgetIntent rawBudgetIntent,
            List<String> warnings
    ) {
        Tier tier = TIERS.get(Math.max(0, Math.min(index, TIERS.size() - 1)));
        List<PartCandidate> parts = recommendation.items().stream()
                .map(this::partCandidate)
                .toList();
        Map<String, Object> parsedContext = engineResponse.parsedContext() == null ? Map.of() : engineResponse.parsedContext();
        int totalPrice = totalPrice(parts, parsedContext);
        Integer userBudget = effectiveBudget(parsedContext, rawBudgetIntent);
        boolean hardConstraintOverBudget = userBudget != null
                && totalPrice > userBudget
                && hasEffectiveHardConstraint(parsedContext, rawBudgetIntent);
        int toolBudget = toolBudgetForBuild(totalPrice, userBudget, rawBudgetIntent, hardConstraintOverBudget);
        List<Map<String, Object>> toolResults = toolResults(parts, toolBudget, warnings);
        List<String> buildWarnings = new ArrayList<>(toolWarnings(toolResults));
        if (hardConstraintOverBudget) {
            buildWarnings.add("HARD_CONSTRAINT_OVER_BUDGET");
            buildWarnings.add("명시한 부품 조건을 지키기 위해 예산을 초과했습니다.");
        }
        List<Map<String, Object>> items = parts.stream()
                .map(part -> partItem(part, "AI 엔진 내부 자산 추천", quantityForRecommendation(part, parsedContext)))
                .toList();
        return MockData.map(
                "id", "ai-engine-" + (index + 1) + "-" + slug(recommendation.name()),
                "tier", tier.id(),
                "label", tier.label(),
                "title", recommendation.name(),
                "summary", recommendation.summary(),
                "recommendedFor", recommendation.recommendedFor(),
                "totalPrice", totalPrice,
                "badges", badges(tier.title(), parsedContext),
                "budgetWon", toolBudget,
                "budgetLabel", userBudget == null ? "예산 미지정" : formatBudgetLabel(userBudget),
                "tierLabel", tier.title(),
                "appliedPartCategories", List.of(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(buildWarnings),
                "confidence", firstText(recommendation.confidence(), confidence(toolResults, buildWarnings)),
                "evidenceIds", engineResponse.evidenceIds()
        );
    }

    private PartCandidate partCandidateFromDraftItem(Map<String, Object> item) {
        String partId = text(item.get("partId"));
        String category = text(item.get("category"));
        if (partId == null || category == null) {
            return null;
        }
        return new PartCandidate(
                null,
                partId,
                category,
                firstText(text(item.get("name")), categoryLabel(category)),
                text(item.get("manufacturer")),
                firstNumber(item.get("currentPrice"), item.get("price"), item.get("unitPriceAtAdd"), item.get("lineTotal")) == null
                        ? 0
                        : firstNumber(item.get("currentPrice"), item.get("price"), item.get("unitPriceAtAdd"), item.get("lineTotal")),
                objectMap(item.get("attributes"))
        );
    }

    private boolean hasBlockingToolFailure(List<Map<String, Object>> toolResults) {
        return toolResults.stream()
                .anyMatch(result -> "FAIL".equals(text(result.get("status"))));
    }

    private Map<String, Object> findDraftItem(List<Map<String, Object>> draftItems, String category, String message) {
        if (category != null) {
            return draftItems.stream()
                    .filter(item -> category.equals(text(item.get("category"))))
                    .findFirst()
                    .orElse(Map.of());
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return draftItems.stream()
                .filter(item -> normalized.contains(String.valueOf(item.get("name")).toLowerCase(Locale.ROOT))
                        || normalized.contains(String.valueOf(item.get("manufacturer")).toLowerCase(Locale.ROOT)))
                .findFirst()
                .orElse(Map.of());
    }

    private static boolean containsAnyNormalized(String normalized, String... keywords) {
        for (String keyword : keywords) {
            if (normalized.contains(normalizeCommand(keyword))) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeCommand(String message) {
        return message == null ? "" : message.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private void logBuildChatPath(
            String pathType,
            long startedNanos,
            Long userId,
            String requestedAiProfile,
            boolean cacheHit,
            BuildChatGuardStats guardStats
    ) {
        logBuildChatPath(pathType, startedNanos, userId, requestedAiProfile, cacheHit, guardStats, null);
    }

    private void logBuildChatPath(
            String pathType,
            long startedNanos,
            Long userId,
            String requestedAiProfile,
            boolean cacheHit,
            BuildChatGuardStats guardStats,
            String stageSummary
    ) {
        long latencyMs = Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
        BuildChatGuardStats stats = guardStats == null ? BuildChatGuardStats.empty() : guardStats;
        log.info(
                "Build Chat pathType={} latencyMs={} userId={} requestedAiProfile={} cacheHit={} budgetGuardDropped={} blockingFailDropped={} routeFallbackUsed={} stages=[{}]",
                pathType,
                latencyMs,
                userId,
                requestedAiProfile,
                cacheHit,
                stats.budgetGuardDropped,
                stats.blockingFailDropped,
                stats.routeFallbackUsed,
                stageSummary == null ? "" : stageSummary
        );
    }

    private static Integer parseCapacityGb(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Matcher matcher = CAPACITY_GB_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private static Integer parseWattage(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        Matcher matcher = WATT_PATTERN.matcher(normalized);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : null;
    }

    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, int budgetWon, List<String> warnings) {
        try {
            return toolCheckService.checkBuild(parts.stream().map(BuildChatService::toolPart).toList(), budgetWon);
        } catch (RuntimeException error) {
            warnings.add("Tool 검증을 완료하지 못했습니다: " + error.getMessage());
            return List.of();
        }
    }

    private List<String> toolWarnings(List<Map<String, Object>> toolResults) {
        return toolResults.stream()
                .filter(result -> !"PASS".equals(text(result.get("status"))))
                .map(result -> text(result.get("summary")))
                .filter(Objects::nonNull)
                .toList();
    }

    private String confidence(List<Map<String, Object>> toolResults, List<String> warnings) {
        if (!warnings.isEmpty() && toolResults.isEmpty()) {
            return "LOW";
        }
        boolean hasFail = toolResults.stream().anyMatch(result -> "FAIL".equals(text(result.get("status"))));
        if (hasFail) {
            return "LOW";
        }
        boolean hasWarn = toolResults.stream().anyMatch(result -> "WARN".equals(text(result.get("status"))));
        return hasWarn ? "MEDIUM" : "HIGH";
    }

    private List<String> buildWarnings(List<Map<String, Object>> builds) {
        return distinct(builds.stream()
                .flatMap(build -> stringList(build.get("warnings")).stream())
                .toList());
    }

    private PartCandidate partByPublicId(String publicId) {
        if (publicId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currentBuilds의 partId가 비어 있습니다.");
        }
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE public_id = ?::uuid
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """, publicId)
                .stream()
                .findFirst()
                .map(this::partCandidate)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "AI 추천 부품을 찾을 수 없습니다."));
    }

    private List<AiChatEngineResponse.PartRecommendation> partRecommendations(String category, int limit) {
        if (category == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price,
                               p.attributes,
                               b.score AS benchmark_score,
                               b.summary AS benchmark_summary
                        FROM parts p
                        LEFT JOIN LATERAL (
                          SELECT score, summary
                          FROM benchmark_summaries bs
                          WHERE bs.part_id = p.id
                            AND bs.deleted_at IS NULL
                          ORDER BY bs.score DESC NULLS LAST, bs.created_at DESC
                          LIMIT 1
                        ) b ON true
                        WHERE p.category = ?
                          AND p.status = 'ACTIVE'
                          AND p.deleted_at IS NULL
                          AND p.price IS NOT NULL
                        ORDER BY b.score DESC NULLS LAST, p.price ASC, p.name ASC
                        LIMIT ?
                        """, category, Math.max(1, limit))
                .stream()
                .map(this::partRecommendation)
                .toList();
    }

    private AiChatEngineResponse.PartRecommendation partRecommendation(Map<String, Object> row) {
        Map<String, Object> attributes = new LinkedHashMap<>(objectMap(DbValueMapper.json(row, "attributes", Map.of())));
        Integer benchmarkScore = DbValueMapper.integer(row, "benchmark_score");
        String benchmarkSummary = DbValueMapper.string(row, "benchmark_summary");
        if (benchmarkScore != null) {
            attributes.put("_benchmarkScore", benchmarkScore);
            attributes.put("benchmarkScore", benchmarkScore);
        }
        if (benchmarkSummary != null) {
            attributes.put("_benchmarkSummary", benchmarkSummary);
        }
        return new AiChatEngineResponse.PartRecommendation(
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                attributes
        );
    }

    private List<PartCandidate> pricePartCandidates(String category, int limit) {
        if (category == null) {
            return List.of();
        }
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               category,
                               name,
                               manufacturer,
                               price,
                               attributes
                        FROM parts
                        WHERE category = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND price IS NOT NULL
                        ORDER BY price ASC, name ASC
                        LIMIT ?
                        """, category, Math.max(1, limit))
                .stream()
                .map(this::partCandidate)
                .toList();
    }

    private static List<String> fallbackCategories(boolean includeGpu, boolean includeCooler) {
        List<String> categories = new ArrayList<>(List.of("CPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE"));
        if (includeGpu) {
            categories.add(3, "GPU");
        }
        if (includeCooler) {
            categories.add("COOLER");
        }
        return categories;
    }

    private static int fallbackCandidateLimit(String category, boolean includeGpu) {
        return switch (category) {
            case "MOTHERBOARD", "GPU" -> includeGpu ? 8 : 6;
            case "CPU", "RAM", "STORAGE", "PSU", "CASE", "COOLER" -> 5;
            default -> 3;
        };
    }

    private PartCandidate partCandidate(Map<String, Object> row) {
        return new PartCandidate(
                longValue(row.get("internal_id")),
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "category"),
                DbValueMapper.string(row, "name"),
                DbValueMapper.string(row, "manufacturer"),
                DbValueMapper.integer(row, "price"),
                objectMap(DbValueMapper.json(row, "attributes", Map.of()))
        );
    }

    private PartCandidate partCandidate(AiChatEngineResponse.PartRecommendation part) {
        return new PartCandidate(
                null,
                part.partId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes() == null ? Map.of() : part.attributes()
        );
    }

    private Map<String, Object> partItem(PartCandidate part, String fallbackNote) {
        return partItem(part, fallbackNote, defaultQuantity(part.category()));
    }

    private Map<String, Object> partItem(PartCandidate part, String fallbackNote, int quantity) {
        return MockData.map(
                "partId", part.publicId(),
                "category", part.category(),
                "name", part.name(),
                "manufacturer", part.manufacturer(),
                "quantity", quantity,
                "price", part.price(),
                "note", firstText(text(part.attributes().get("shortSpec")), fallbackNote)
        );
    }

    private static ToolBuildPart toolPart(PartCandidate part) {
        return new ToolBuildPart(
                part.internalId(),
                part.publicId(),
                part.category(),
                part.name(),
                part.manufacturer(),
                part.price(),
                part.attributes()
        );
    }

    private static String answerType(AiChatIntent intent) {
        return switch (intent) {
            case FULL_BUILD_RECOMMEND -> "BUDGET";
            case PART_RECOMMEND, BUILD_MODIFY -> "PART";
            case PRICE_ALERT_HELP, EXPLAIN, ASK_FOLLOW_UP -> "GENERAL";
        };
    }

    private static int defaultQuantity(String category) {
        return "RAM".equals(category) ? 2 : 1;
    }

    private static int quantityForRecommendation(PartCandidate part, Map<String, Object> parsedContext) {
        Integer targetQuantity = parsedContext == null ? null : numberValue(parsedContext.get("targetQuantity"));
        if (targetQuantity != null && targetQuantity > 0) {
            return Math.max(1, Math.min(9, targetQuantity));
        }
        return defaultQuantity(part.category());
    }

    private static int quantityForBudgetFallback(PartCandidate part, int ramQuantity) {
        return "RAM".equals(part.category()) ? Math.max(1, Math.min(4, ramQuantity)) : defaultQuantity(part.category());
    }

    private static boolean hasHardConstraint(Map<String, Object> parsedContext) {
        return "MUST_INCLUDE".equals(text(parsedContext.get("hardConstraintPolicy")))
                || !stringList(parsedContext.get("requiredGpuClasses")).isEmpty()
                || !stringList(parsedContext.get("requiredPartKeywords")).isEmpty();
    }

    private static boolean hasEffectiveHardConstraint(Map<String, Object> parsedContext, BudgetIntent rawBudgetIntent) {
        return hasHardConstraint(parsedContext) || (rawBudgetIntent != null && rawBudgetIntent.explicitHardConstraint());
    }

    private static Integer effectiveBudget(Map<String, Object> parsedContext, BudgetIntent rawBudgetIntent) {
        if (rawBudgetIntent != null && rawBudgetIntent.hasBudget()) {
            return rawBudgetIntent.budget();
        }
        return numberValue(parsedContext.get("budget"));
    }

    private static int toolBudgetForBuild(
            int totalPrice,
            Integer userBudget,
            BudgetIntent rawBudgetIntent,
            boolean hardConstraintOverBudget
    ) {
        if (userBudget == null || userBudget <= 0 || hardConstraintOverBudget) {
            return totalPrice;
        }
        if (rawBudgetIntent != null && rawBudgetIntent.hasBudget() && "MAX".equals(rawBudgetIntent.mode())) {
            return userBudget;
        }
        return totalPrice;
    }

    private static boolean withinBudgetGuard(Map<String, Object> build, Map<String, Object> parsedContext, BudgetIntent rawBudgetIntent) {
        Map<String, Object> context = parsedContext == null ? Map.of() : parsedContext;
        Integer budget = effectiveBudget(context, rawBudgetIntent);
        if (budget == null || budget <= 0 || hasEffectiveHardConstraint(context, rawBudgetIntent)) {
            return true;
        }
        Integer totalPrice = numberValue(build.get("totalPrice"));
        if (totalPrice == null) {
            return true;
        }
        String budgetMode = rawBudgetIntent != null && rawBudgetIntent.hasBudget() ? rawBudgetIntent.mode() : budgetMode(context);
        if ("MIN".equals(budgetMode)) {
            return totalPrice >= budget;
        }
        if ("MAX".equals(budgetMode)) {
            return totalPrice <= budget;
        }
        if ("TARGET".equals(budgetMode) || "USER_BUDGET".equals(text(context.get("budgetPolicy")))) {
            return totalPrice >= Math.floor(budget * 0.875) && totalPrice <= Math.ceil(budget * 1.125);
        }
        return true;
    }

    private static boolean hasRawHardPartConstraint(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        String compact = normalizeCommand(message);
        return EXPLICIT_GPU_MODEL.matcher(normalized).find()
                || EXPLICIT_CPU_MODEL.matcher(normalized).find()
                || containsAnyNormalized(compact, "정품멀티팩", "리안리216", "lianli216");
    }

    private static String budgetMode(Map<String, Object> parsedContext) {
        String mode = text(parsedContext.get("budgetMode"));
        if (mode == null) {
            return "UNSPECIFIED";
        }
        String upper = mode.toUpperCase(Locale.ROOT);
        return List.of("TARGET", "MAX", "MIN", "OPEN", "UNSPECIFIED").contains(upper) ? upper : "UNSPECIFIED";
    }

    private static List<String> badges(String tierTitle, Map<String, Object> parsedContext) {
        List<String> badges = new ArrayList<>();
        badges.add(tierTitle);
        String budgetPolicy = text(parsedContext.get("budgetPolicy"));
        if (budgetPolicy != null) {
            badges.add(budgetPolicy);
        }
        stringList(parsedContext.get("requiredGpuClasses")).forEach(badges::add);
        return distinct(badges);
    }

    private static String formatBudgetLabel(int budgetWon) {
        return budgetWon % 10_000 == 0 ? (budgetWon / 10_000) + "만원" : String.format("%,d원", budgetWon);
    }

    private static int totalPrice(List<PartCandidate> parts) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * defaultQuantity(part.category()))
                .sum();
    }

    private static int totalPrice(List<PartCandidate> parts, int ramQuantity) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * quantityForBudgetFallback(part, ramQuantity))
                .sum();
    }

    private static int totalPrice(List<PartCandidate> parts, Map<String, Object> parsedContext) {
        return parts.stream()
                .mapToInt(part -> (part.price() == null ? 0 : part.price()) * quantityForRecommendation(part, parsedContext))
                .sum();
    }

    private static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private static String slug(String value) {
        String normalized = text(value);
        if (normalized == null) {
            return "build";
        }
        String slug = normalized.toLowerCase(Locale.ROOT).replaceAll("[^0-9a-z가-힣]+", "-");
        return slug.replaceAll("(^-+|-+$)", "");
    }

    private static List<String> distinct(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static String requireText(Object value, String message) {
        String text = text(value);
        if (text == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
        }
        return text;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() || "null".equalsIgnoreCase(text) ? null : text;
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Integer numberValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Integer.parseInt(text.replace(",", ""));
    }

    private static Double doubleValue(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        String text = text(value);
        if (text == null) {
            return null;
        }
        return Double.parseDouble(text.replace(",", ""));
    }

    private static boolean isUuid(String value) {
        return value != null && value.matches("(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    private static Integer firstNumber(Object... values) {
        for (Object value : values) {
            Integer number = numberValue(value);
            if (number != null) {
                return number;
            }
        }
        return null;
    }

    private static Long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return value == null ? null : Long.valueOf(value.toString());
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .map(BuildChatService::text)
                    .filter(Objects::nonNull)
                    .toList();
        }
        return List.of();
    }

    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (Object item : list) {
            result.add(objectMap(item));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> result.put(String.valueOf(key), mapValue));
            return result;
        }
        return Map.of();
    }

    private record Tier(String id, String label, String title) {
    }

    private record CategoryKeywords(String category, List<String> keywords) {
    }

    record BudgetIntent(Integer budget, String mode, boolean explicitHardConstraint) {
        static BudgetIntent empty() {
            return new BudgetIntent(null, "UNSPECIFIED", false);
        }

        boolean hasBudget() {
            return budget != null && budget > 0;
        }
    }

    private static final class BuildChatGuardStats {
        int budgetGuardDropped;
        int blockingFailDropped;
        boolean routeFallbackUsed;

        static BuildChatGuardStats empty() {
            return new BuildChatGuardStats();
        }

        static BuildChatGuardStats routeFallback() {
            BuildChatGuardStats stats = new BuildChatGuardStats();
            stats.routeFallbackUsed = true;
            return stats;
        }
    }

    private record BenchmarkSnapshot(Double score, String summary) {
    }

    private record PartCandidate(
            Long internalId,
            String publicId,
            String category,
            String name,
            String manufacturer,
            Integer price,
            Map<String, Object> attributes
    ) {
    }
}

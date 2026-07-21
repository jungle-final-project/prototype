package com.buildgraph.prototype.quote;

import com.buildgraph.prototype.build.BuildEvaluationService;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.HistoryEntry;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.Snapshot;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.SnapshotItem;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.UnavailableItem;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class QuoteDraftHistoryService {
    private static final List<String> CATEGORY_ORDER = List.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );

    private final QuoteDraftHistoryStore historyStore;
    private final QuoteDraftQueryService quoteDraftQueryService;
    private final QuoteDraftReadCache draftReadCache;
    private final CurrentUserService currentUserService;
    private final PartQuery partQuery;
    private final BuildEvaluationService buildEvaluationService;
    private final ToolCheckService toolCheckService;
    private final TransactionTemplate transactionTemplate;

    public QuoteDraftHistoryService(
            QuoteDraftHistoryStore historyStore,
            QuoteDraftQueryService quoteDraftQueryService,
            QuoteDraftReadCache draftReadCache,
            CurrentUserService currentUserService,
            PartQuery partQuery,
            BuildEvaluationService buildEvaluationService,
            ToolCheckService toolCheckService,
            PlatformTransactionManager transactionManager
    ) {
        this.historyStore = historyStore;
        this.quoteDraftQueryService = quoteDraftQueryService;
        this.draftReadCache = draftReadCache;
        this.currentUserService = currentUserService;
        this.partQuery = partQuery;
        this.buildEvaluationService = buildEvaluationService;
        this.toolCheckService = toolCheckService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    public Map<String, Object> list(String authorization) {
        long userId = currentUserService.requireUser(authorization).internalId();
        Map<String, Object> draft = historyStore.activeDraft(userId);
        if (draft == null) {
            return Map.of(
                    "items", List.of(),
                    "retentionDays", 30,
                    "maxItems", 20,
                    "maxProblemItems", 5
            );
        }
        long draftId = longValue(draft.get("internal_id"));
        List<Map<String, Object>> items = historyStore.entries(draftId).stream()
                .map(this::entrySummary)
                .toList();
        return MockData.map(
                "items", items,
                "retentionDays", 30,
                "maxItems", 20,
                "maxProblemItems", 5
        );
    }

    public Map<String, Object> comparison(
            String authorization,
            String historyId,
            String game,
            String resolution
    ) {
        long userId = currentUserService.requireUser(authorization).internalId();
        Map<String, Object> draft = requireDraft(userId);
        long draftId = longValue(draft.get("internal_id"));
        HistoryEntry entry = historyStore.requireEntry(draftId, historyId);
        Snapshot current = historyStore.snapshot(draftId);
        String normalizedGame = normalizedGame(game);
        String normalizedResolution = normalizedResolution(resolution);

        Evaluation pastEvaluation = evaluate(entry.snapshot(), normalizedGame, normalizedResolution);
        Evaluation currentEvaluation = evaluate(current, normalizedGame, normalizedResolution);
        List<UnavailableItem> unavailableItems = historyStore.unavailableItems(entry.snapshot());

        return MockData.map(
                "history", entrySummary(entry),
                "past", snapshotView(entry.snapshot(), pastEvaluation),
                "current", snapshotView(current, currentEvaluation),
                "differences", differences(current, entry.snapshot()),
                "issueChanges", issueChanges(currentEvaluation.issues(), pastEvaluation.issues()),
                "game", normalizedGame,
                "resolution", normalizedResolution,
                "restorable", unavailableItems.isEmpty(),
                "unavailableItems", unavailableItems.stream().map(this::unavailableItemMap).toList(),
                "requiresCompatibilityConfirmation", pastEvaluation.hasToolFail() || !pastEvaluation.available(),
                "evaluationBasis", "CURRENT_CATALOG_DATA",
                "historicalPriceBasis", "SNAPSHOT_UNIT_PRICE"
        );
    }

    public Map<String, Object> restore(
            String authorization,
            String historyId,
            Map<String, Object> request,
            String changeGroup
    ) {
        long userId = currentUserService.requireUser(authorization).internalId();
        boolean confirmCompatibilityRisk = Boolean.TRUE.equals(request.get("confirmCompatibilityRisk"));

        transactionTemplate.executeWithoutResult(status -> {
            Map<String, Object> draft = historyStore.lockActiveDraft(userId);
            if (draft == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "활성 견적초안을 찾을 수 없습니다.");
            }
            long draftId = longValue(draft.get("internal_id"));
            HistoryEntry entry = historyStore.requireEntry(draftId, historyId);
            Snapshot current = historyStore.snapshot(draftId);
            if (sameSnapshot(current, entry.snapshot())) {
                return;
            }
            List<UnavailableItem> unavailableItems = historyStore.unavailableItems(entry.snapshot());
            if (!unavailableItems.isEmpty()) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "HISTORY_NOT_RESTORABLE",
                        "현재 판매 중이 아닌 부품이 포함되어 과거 견적을 복원할 수 없습니다.",
                        Map.of("unavailableItems", unavailableItems.stream().map(this::unavailableItemMap).toList())
                );
            }

            Evaluation evaluation = evaluate(entry.snapshot(), null, null);
            if ((evaluation.hasToolFail() || !evaluation.available()) && !confirmCompatibilityRisk) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "HISTORY_RESTORE_CONFIRM_REQUIRED",
                        evaluation.available()
                                ? "호환성 또는 장착 문제가 있는 과거 견적입니다. 경고를 확인한 뒤 다시 복원해 주세요."
                                : "현재 평가를 완료하지 못했습니다. 위험을 확인한 뒤 다시 복원해 주세요.",
                        Map.of(
                                "issues", evaluation.issues(),
                                "evaluationAvailable", evaluation.available()
                        )
                );
            }

            historyStore.captureBeforeMutation(
                    draftId,
                    changeGroup,
                    "RESTORE",
                    entry.relatedCategories(),
                    Map.of("historyId", historyId)
            );
            historyStore.replaceDraft(draftId, entry.snapshot());
        });

        draftReadCache.invalidate(userId);
        return quoteDraftQueryService.current(authorization);
    }

    private Evaluation evaluate(Snapshot snapshot, String game, String resolution) {
        if (snapshot.items().isEmpty()) {
            return Evaluation.empty();
        }
        try {
            List<String> ids = snapshot.items().stream().map(SnapshotItem::partId).toList();
            Map<String, Integer> quantities = new LinkedHashMap<>();
            snapshot.items().forEach(item -> quantities.put(item.partId(), item.quantity()));
            List<ToolBuildPart> parts = partQuery.partsForPublicIdQuantities(ids, quantities);
            BuildEvaluationService.BuildEvaluation evaluation = buildEvaluationService.evaluate(parts, null, null, null);
            Map<String, Object> score = scoreSummary(evaluation.compositeScore());
            List<Map<String, Object>> issues = issues(evaluation.buildAssessment());
            Map<String, Object> fps = fps(parts, game, resolution);
            boolean hasToolFail = evaluation.toolResults().stream()
                    .anyMatch(tool -> "FAIL".equalsIgnoreCase(text(tool.get("status"))));
            return new Evaluation(score, issues, fps, hasToolFail, true);
        } catch (RuntimeException exception) {
            return new Evaluation(null, List.of(), null, false, false);
        }
    }

    private Map<String, Object> fps(List<ToolBuildPart> parts, String game, String resolution) {
        if (game == null || resolution == null) {
            return null;
        }
        List<String> partIds = parts.stream()
                .filter(part -> "CPU".equals(part.category()) || "GPU".equals(part.category()))
                .map(ToolBuildPart::publicId)
                .toList();
        if (partIds.isEmpty()) {
            return null;
        }
        Map<String, Object> result = toolCheckService.checkTool("performance", Map.of(
                "partIds", partIds,
                "context", Map.of("game", game, "resolution", resolution)
        ));
        Map<String, Object> details = objectMap(result.get("details"));
        List<Map<String, Object>> evidence = objectMaps(details.get("gameFpsEvidence"));
        if (evidence.isEmpty()) {
            return null;
        }
        Map<String, Object> first = evidence.get(0);
        return MockData.map(
                "gameTitle", first.get("gameTitle"),
                "gameKey", first.get("gameKey"),
                "resolution", first.get("resolution"),
                "graphicsPreset", first.get("graphicsPreset"),
                "avgFps", first.get("avgFps"),
                "onePercentLowFps", first.get("onePercentLowFps"),
                "sourceName", first.get("sourceName")
        );
    }

    private Map<String, Object> entrySummary(HistoryEntry entry) {
        return MockData.map(
                "id", entry.id(),
                "actionType", entry.actionType(),
                "actionLabel", entry.actionLabel(),
                "relatedCategories", entry.relatedCategories(),
                "totalPrice", entry.snapshot().totalPrice(),
                "itemCount", entry.snapshot().itemCount(),
                "evaluationStatus", entry.evaluationStatus(),
                "score", entry.evaluationScore(),
                "maxScore", entry.evaluationMaxScore(),
                "issueCodes", entry.evaluationIssueCodes(),
                "evaluatedAt", entry.evaluatedAt(),
                "createdAt", entry.createdAt(),
                "expiresAt", entry.expiresAt()
        );
    }

    private Map<String, Object> snapshotView(Snapshot snapshot, Evaluation evaluation) {
        return MockData.map(
                "items", snapshot.items().stream().map(this::snapshotItemMap).toList(),
                "totalPrice", snapshot.totalPrice(),
                "itemCount", snapshot.itemCount(),
                "compositeScore", evaluation.score(),
                "fps", evaluation.fps(),
                "issues", evaluation.issues(),
                "evaluationAvailable", evaluation.available()
        );
    }

    private List<Map<String, Object>> differences(Snapshot from, Snapshot to) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String category : CATEGORY_ORDER) {
            List<SnapshotItem> before = itemsForCategory(from, category);
            List<SnapshotItem> after = itemsForCategory(to, category);
            if (sameItems(before, after)) {
                continue;
            }
            result.add(MockData.map(
                    "category", category,
                    "categoryLabel", QuoteDraftHistoryStore.categoryLabel(category),
                    "changeType", changeType(before, after),
                    "beforeItems", before.stream().map(this::snapshotItemMap).toList(),
                    "afterItems", after.stream().map(this::snapshotItemMap).toList()
            ));
        }
        return result;
    }

    private Map<String, Object> issueChanges(List<Map<String, Object>> from, List<Map<String, Object>> to) {
        Set<String> fromCodes = issueCodes(from);
        Set<String> toCodes = issueCodes(to);
        return Map.of(
                "added", to.stream().filter(issue -> !fromCodes.contains(text(issue.get("code")))).toList(),
                "resolved", from.stream().filter(issue -> !toCodes.contains(text(issue.get("code")))).toList()
        );
    }

    private static Set<String> issueCodes(List<Map<String, Object>> issues) {
        Set<String> result = new LinkedHashSet<>();
        issues.stream().map(issue -> text(issue.get("code"))).filter(java.util.Objects::nonNull).forEach(result::add);
        return result;
    }

    private static Map<String, Object> scoreSummary(Map<String, Object> score) {
        if (score == null || score.isEmpty()) {
            return null;
        }
        return MockData.map(
                "score", score.get("score"),
                "maxScore", score.get("maxScore"),
                "grade", score.get("grade"),
                "label", score.get("label"),
                "summary", score.get("summary")
        );
    }

    private static List<Map<String, Object>> issues(Map<String, Object> assessment) {
        return objectMaps(assessment == null ? null : assessment.get("cautions")).stream()
                .limit(6)
                .map(issue -> MockData.map(
                        "code", issue.get("code"),
                        "severity", issue.get("severity"),
                        "title", issue.get("title"),
                        "description", issue.get("description"),
                        "relatedCategories", issue.get("relatedCategories")
                ))
                .toList();
    }

    private Map<String, Object> snapshotItemMap(SnapshotItem item) {
        return MockData.map(
                "partId", item.partId(),
                "category", item.category(),
                "name", item.name(),
                "manufacturer", item.manufacturer(),
                "quantity", item.quantity(),
                "unitPriceAtAdd", item.unitPriceAtAdd(),
                "lineTotal", item.lineTotal()
        );
    }

    private Map<String, Object> unavailableItemMap(UnavailableItem item) {
        return Map.of(
                "partId", item.partId(),
                "category", item.category(),
                "name", item.name()
        );
    }

    private static List<SnapshotItem> itemsForCategory(Snapshot snapshot, String category) {
        return snapshot.items().stream()
                .filter(item -> category.equals(item.category()))
                .sorted(Comparator.comparing(SnapshotItem::partId))
                .toList();
    }

    private static boolean sameItems(List<SnapshotItem> left, List<SnapshotItem> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            SnapshotItem a = left.get(index);
            SnapshotItem b = right.get(index);
            if (!a.partId().equals(b.partId()) || a.quantity() != b.quantity()) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameSnapshot(Snapshot left, Snapshot right) {
        if (left.items().size() != right.items().size()) {
            return false;
        }
        List<String> leftItems = left.items().stream()
                .map(QuoteDraftHistoryService::snapshotIdentity)
                .sorted()
                .toList();
        List<String> rightItems = right.items().stream()
                .map(QuoteDraftHistoryService::snapshotIdentity)
                .sorted()
                .toList();
        return leftItems.equals(rightItems);
    }

    private static String snapshotIdentity(SnapshotItem item) {
        return item.category() + ":" + item.partId() + ":" + item.quantity() + ":" + item.unitPriceAtAdd();
    }

    private static String changeType(List<SnapshotItem> before, List<SnapshotItem> after) {
        if (before.isEmpty()) return "ADDED";
        if (after.isEmpty()) return "REMOVED";
        Set<String> beforeIds = before.stream().map(SnapshotItem::partId).collect(java.util.stream.Collectors.toSet());
        Set<String> afterIds = after.stream().map(SnapshotItem::partId).collect(java.util.stream.Collectors.toSet());
        return beforeIds.equals(afterIds) ? "QUANTITY_CHANGED" : "REPLACED";
    }

    private Map<String, Object> requireDraft(long userId) {
        Map<String, Object> draft = historyStore.activeDraft(userId);
        if (draft == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "활성 견적초안을 찾을 수 없습니다.");
        }
        return draft;
    }

    private static String normalizedGame(String value) {
        String normalized = text(value);
        if (normalized == null) return "pubg";
        if (normalized.length() > 80) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "game 값이 너무 깁니다.");
        }
        return normalized.toLowerCase(Locale.ROOT);
    }

    private static String normalizedResolution(String value) {
        String normalized = text(value);
        if (normalized == null) return "qhd";
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (!Set.of("fhd", "qhd", "4k").contains(normalized)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "resolution은 fhd, qhd, 4k만 지원합니다.");
        }
        return normalized;
    }

    private static long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }

    private record Evaluation(
            Map<String, Object> score,
            List<Map<String, Object>> issues,
            Map<String, Object> fps,
            boolean hasToolFail,
            boolean available
    ) {
        static Evaluation empty() {
            return new Evaluation(null, List.of(), null, false, true);
        }
    }
}

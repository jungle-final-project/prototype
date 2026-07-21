package com.buildgraph.prototype.quote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.build.BuildEvaluationService;
import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.HistoryEntry;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.Snapshot;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.SnapshotItem;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.UnavailableItem;
import com.buildgraph.prototype.user.CurrentUserService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

class QuoteDraftHistoryServiceTest {
    private static final String AUTH = "Bearer user";
    private static final String HISTORY_ID = "11111111-1111-4111-8111-111111111111";
    private static final SnapshotItem OLD_GPU = new SnapshotItem(
            "00000000-0000-4000-8000-000000000201", "GPU", "RTX 5080", "NVIDIA", 1, 1_800_000
    );
    private static final SnapshotItem NEW_GPU = new SnapshotItem(
            "00000000-0000-4000-8000-000000000202", "GPU", "RTX 5090", "NVIDIA", 1, 3_500_000
    );

    private QuoteDraftHistoryStore historyStore;
    private QuoteDraftQueryService quoteDraftQueryService;
    private QuoteDraftReadCache draftReadCache;
    private CurrentUserService currentUserService;
    private PartQuery partQuery;
    private BuildEvaluationService buildEvaluationService;
    private ToolCheckService toolCheckService;
    private QuoteDraftHistoryService service;

    @BeforeEach
    void setUp() {
        historyStore = mock(QuoteDraftHistoryStore.class);
        quoteDraftQueryService = mock(QuoteDraftQueryService.class);
        draftReadCache = mock(QuoteDraftReadCache.class);
        currentUserService = mock(CurrentUserService.class);
        partQuery = mock(PartQuery.class);
        buildEvaluationService = mock(BuildEvaluationService.class);
        toolCheckService = mock(ToolCheckService.class);
        PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any(TransactionDefinition.class)))
                .thenReturn(mock(TransactionStatus.class));
        when(currentUserService.requireUser(AUTH)).thenReturn(new CurrentUserService.CurrentUser(
                42L, "user-id", "user@example.com", "사용자", "USER", null
        ));
        when(historyStore.activeDraft(42L)).thenReturn(Map.of("internal_id", 7L));
        when(historyStore.lockActiveDraft(42L)).thenReturn(Map.of("internal_id", 7L));

        service = new QuoteDraftHistoryService(
                historyStore,
                quoteDraftQueryService,
                draftReadCache,
                currentUserService,
                partQuery,
                buildEvaluationService,
                toolCheckService,
                transactionManager
        );
    }

    @Test
    void comparisonUsesSnapshotPriceButCurrentEvaluationFacts() {
        Snapshot past = snapshot(OLD_GPU);
        Snapshot current = snapshot(NEW_GPU);
        when(historyStore.requireEntry(7L, HISTORY_ID)).thenReturn(entry(past));
        when(historyStore.snapshot(7L)).thenReturn(current);
        when(historyStore.unavailableItems(past)).thenReturn(List.of());
        ToolBuildPart toolPart = toolPart();
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap())).thenReturn(List.of(toolPart));
        when(buildEvaluationService.evaluate(anyList(), isNull(), isNull(), isNull())).thenReturn(evaluation(false));
        when(toolCheckService.checkTool(eq("performance"), anyMap())).thenReturn(Map.of(
                "details", Map.of("gameFpsEvidence", List.of(Map.of(
                        "gameTitle", "배틀그라운드",
                        "gameKey", "pubg",
                        "resolution", "QHD",
                        "graphicsPreset", "중간",
                        "avgFps", 140,
                        "sourceName", "fixture"
                )))
        ));

        Map<String, Object> result = service.comparison(AUTH, HISTORY_ID, "pubg", "qhd");

        assertThat(map(result.get("past"))).containsEntry("totalPrice", 1_800_000);
        assertThat(map(result.get("current"))).containsEntry("totalPrice", 3_500_000);
        assertThat(list(result.get("differences"))).singleElement().satisfies(value -> {
            Map<String, Object> difference = map(value);
            assertThat(difference).containsEntry("category", "GPU").containsEntry("changeType", "REPLACED");
            assertThat(list(difference.get("beforeItems"))).singleElement().satisfies(item ->
                    assertThat(map(item)).containsEntry("name", NEW_GPU.name()));
            assertThat(list(difference.get("afterItems"))).singleElement().satisfies(item ->
                    assertThat(map(item)).containsEntry("name", OLD_GPU.name()));
        });
        assertThat(result)
                .containsEntry("evaluationBasis", "CURRENT_CATALOG_DATA")
                .containsEntry("historicalPriceBasis", "SNAPSHOT_UNIT_PRICE")
                .containsEntry("restorable", true);
    }

    @Test
    void comparisonDescribesWarningsFromCurrentDraftTowardRestoreTarget() {
        Snapshot past = snapshot(OLD_GPU);
        Snapshot current = snapshot(NEW_GPU);
        when(historyStore.requireEntry(7L, HISTORY_ID)).thenReturn(entry(past));
        when(historyStore.snapshot(7L)).thenReturn(current);
        when(historyStore.unavailableItems(past)).thenReturn(List.of());
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap())).thenReturn(List.of(toolPart()));
        when(buildEvaluationService.evaluate(anyList(), isNull(), isNull(), isNull()))
                .thenReturn(evaluation(true), evaluation(false));
        when(toolCheckService.checkTool(eq("performance"), anyMap())).thenReturn(Map.of("details", Map.of()));

        Map<String, Object> result = service.comparison(AUTH, HISTORY_ID, "pubg", "qhd");

        Map<String, Object> issueChanges = map(result.get("issueChanges"));
        assertThat(list(issueChanges.get("added"))).singleElement().satisfies(issue ->
                assertThat(map(issue)).containsEntry("code", "COMPATIBILITY_FAIL"));
        assertThat(list(issueChanges.get("resolved"))).isEmpty();
        assertThat(result).containsEntry("requiresCompatibilityConfirmation", true);
    }

    @Test
    void restoreBlocksUnavailablePartWithoutChangingDraft() {
        Snapshot past = snapshot(OLD_GPU);
        when(historyStore.requireEntry(7L, HISTORY_ID)).thenReturn(entry(past));
        when(historyStore.snapshot(7L)).thenReturn(snapshot(NEW_GPU));
        when(historyStore.unavailableItems(past)).thenReturn(List.of(
                new UnavailableItem(OLD_GPU.partId(), "GPU", OLD_GPU.name())
        ));

        assertThatThrownBy(() -> service.restore(AUTH, HISTORY_ID, Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("HISTORY_NOT_RESTORABLE"));

        verify(historyStore, never()).replaceDraft(any(Long.class), any(Snapshot.class));
    }

    @Test
    void restoreRequiresWarningConfirmationThenCapturesCurrentState() {
        Snapshot past = snapshot(OLD_GPU);
        Snapshot current = snapshot(NEW_GPU);
        when(historyStore.requireEntry(7L, HISTORY_ID)).thenReturn(entry(past));
        when(historyStore.snapshot(7L)).thenReturn(current);
        when(historyStore.unavailableItems(past)).thenReturn(List.of());
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap())).thenReturn(List.of(toolPart()));
        when(buildEvaluationService.evaluate(anyList(), isNull(), isNull(), isNull())).thenReturn(evaluation(true));
        when(quoteDraftQueryService.current(AUTH)).thenReturn(Map.of("status", "ACTIVE"));

        assertThatThrownBy(() -> service.restore(AUTH, HISTORY_ID, Map.of(), null))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.code()).isEqualTo("HISTORY_RESTORE_CONFIRM_REQUIRED"));

        Map<String, Object> restored = service.restore(
                AUTH,
                HISTORY_ID,
                Map.of("confirmCompatibilityRisk", true),
                "22222222-2222-4222-8222-222222222222"
        );

        assertThat(restored).containsEntry("status", "ACTIVE");
        verify(historyStore).captureBeforeMutation(
                7L,
                "22222222-2222-4222-8222-222222222222",
                "RESTORE",
                List.of("GPU"),
                Map.of("historyId", HISTORY_ID)
        );
        verify(historyStore).replaceDraft(7L, past);
        verify(draftReadCache).invalidate(42L);
    }

    @Test
    void restoringIdenticalSnapshotIsNoOp() {
        Snapshot past = snapshot(OLD_GPU);
        when(historyStore.requireEntry(7L, HISTORY_ID)).thenReturn(entry(past));
        when(historyStore.snapshot(7L)).thenReturn(past);
        when(quoteDraftQueryService.current(AUTH)).thenReturn(Map.of("status", "ACTIVE"));

        Map<String, Object> result = service.restore(AUTH, HISTORY_ID, Map.of(), null);

        assertThat(result).containsEntry("status", "ACTIVE");
        verify(historyStore, never()).captureBeforeMutation(any(Long.class), any(), any(), anyList(), anyMap());
        verify(historyStore, never()).replaceDraft(any(Long.class), any(Snapshot.class));
        verifyNoInteractions(partQuery, buildEvaluationService, toolCheckService);
    }

    private static Snapshot snapshot(SnapshotItem item) {
        return new Snapshot(List.of(item), item.lineTotal(), item.quantity());
    }

    private static HistoryEntry entry(Snapshot snapshot) {
        return new HistoryEntry(
                HISTORY_ID,
                "ITEM_REPLACE",
                "GPU 교체 전",
                List.of("GPU"),
                Map.of(),
                snapshot,
                "fingerprint",
                "VALID",
                800,
                1000,
                null,
                List.of(),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                OffsetDateTime.now().plusDays(30)
        );
    }

    private static ToolBuildPart toolPart() {
        return new ToolBuildPart(1L, OLD_GPU.partId(), "GPU", OLD_GPU.name(), "NVIDIA", 1_800_000, Map.of(), 1);
    }

    private static BuildEvaluationService.BuildEvaluation evaluation(boolean fail) {
        return new BuildEvaluationService.BuildEvaluation(
                List.of(toolPart()),
                1_800_000,
                1_800_000,
                List.of(Map.of("tool", "compatibility", "status", fail ? "FAIL" : "PASS")),
                Map.of("score", fail ? 0 : 800, "maxScore", 1000, "grade", fail ? "F" : "A", "label", "평가"),
                Map.of("cautions", fail ? List.of(Map.of(
                        "code", "COMPATIBILITY_FAIL",
                        "severity", "FAIL",
                        "title", "호환 불가",
                        "description", "장착할 수 없습니다.",
                        "relatedCategories", List.of("GPU")
                )) : List.of())
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        return (List<Object>) value;
    }
}

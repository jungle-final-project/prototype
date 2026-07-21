package com.buildgraph.prototype.quote;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.build.BuildEvaluationService;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.EvaluationCandidate;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.Snapshot;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.SnapshotItem;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class QuoteDraftHistoryEvaluationWorkerTest {
    private QuoteDraftHistoryStore historyStore;
    private PartQuery partQuery;
    private BuildEvaluationService buildEvaluationService;
    private QuoteDraftHistoryEvaluationWorker worker;
    private EvaluationCandidate candidate;
    private ToolBuildPart part;

    @BeforeEach
    void setUp() {
        historyStore = mock(QuoteDraftHistoryStore.class);
        partQuery = mock(PartQuery.class);
        buildEvaluationService = mock(BuildEvaluationService.class);
        worker = new QuoteDraftHistoryEvaluationWorker(historyStore, partQuery, buildEvaluationService, 8);
        SnapshotItem item = new SnapshotItem(
                "00000000-0000-4000-8000-000000000201",
                "GPU",
                "RTX 5080",
                "NVIDIA",
                1,
                1_800_000
        );
        candidate = new EvaluationCandidate(
                "11111111-1111-4111-8111-111111111111",
                7L,
                new Snapshot(List.of(item), item.lineTotal(), item.quantity()),
                1
        );
        part = new ToolBuildPart(1L, item.partId(), item.category(), item.name(), item.manufacturer(), item.unitPriceAtAdd(), Map.of(), 1);
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap())).thenReturn(List.of(part));
    }

    @Test
    void storesPositiveScoreAsValidHistory() {
        when(buildEvaluationService.evaluate(anyList(), isNull(), isNull(), isNull()))
                .thenReturn(evaluation(812, "PASS", List.of()));

        worker.evaluate(candidate);

        verify(historyStore).completeEvaluation(
                candidate,
                "VALID",
                812,
                1000,
                null,
                List.of()
        );
    }

    @Test
    void storesToolFailureAsInvalidWithStableIssueCodes() {
        when(buildEvaluationService.evaluate(anyList(), isNull(), isNull(), isNull()))
                .thenReturn(evaluation(0, "FAIL", List.of(Map.of(
                        "code", "CASE_GPU_CLEARANCE_FAIL",
                        "severity", "FAIL"
                ))));

        worker.evaluate(candidate);

        verify(historyStore).completeEvaluation(
                eq(candidate),
                eq("INVALID"),
                eq(0),
                eq(1000),
                anyString(),
                eq(List.of("CASE_GPU_CLEARANCE_FAIL", "TOOL_FAIL_COMPATIBILITY"))
        );
    }

    @Test
    void retriesTransientEvaluationFailureInsteadOfTreatingItAsZeroScore() {
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap()))
                .thenThrow(new IllegalStateException("temporary failure"));

        worker.evaluate(candidate);

        verify(historyStore).scheduleEvaluationRetry(candidate, "EVALUATION_ERROR", 3, 500);
        verify(historyStore, never()).markEvaluationUnavailable(eq(candidate), anyString());
    }

    @Test
    void permanentlyMarksHistoryWhenSnapshotPartIsNoLongerAvailable() {
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap())).thenReturn(List.of());
        when(historyStore.unavailableItems(candidate.snapshot())).thenReturn(List.of(
                new QuoteDraftHistoryStore.UnavailableItem(
                        candidate.snapshot().items().get(0).partId(),
                        "GPU",
                        "RTX 5080"
                )
        ));

        worker.evaluate(candidate);

        verify(historyStore).markEvaluationUnavailable(candidate, "PART_UNAVAILABLE");
        verify(historyStore, never()).scheduleEvaluationRetry(eq(candidate), anyString(), eq(3), eq(500L));
    }

    @Test
    void stopsAfterConfiguredMaximumAttempts() {
        EvaluationCandidate finalAttempt = new EvaluationCandidate(
                candidate.id(), candidate.draftId(), candidate.snapshot(), 3
        );
        when(partQuery.partsForPublicIdQuantities(anyList(), anyMap()))
                .thenThrow(new IllegalStateException("still unavailable"));

        worker.evaluate(finalAttempt);

        verify(historyStore).scheduleEvaluationRetry(finalAttempt, "EVALUATION_ERROR", 3, 2000);
    }

    private BuildEvaluationService.BuildEvaluation evaluation(
            int score,
            String toolStatus,
            List<Map<String, Object>> cautions
    ) {
        return new BuildEvaluationService.BuildEvaluation(
                List.of(part),
                1_800_000,
                1_800_000,
                List.of(Map.of("tool", "compatibility", "status", toolStatus)),
                Map.of("score", score, "maxScore", 1000, "grade", score == 0 ? "F" : "A", "label", "평가"),
                Map.of("cautions", cautions)
        );
    }
}

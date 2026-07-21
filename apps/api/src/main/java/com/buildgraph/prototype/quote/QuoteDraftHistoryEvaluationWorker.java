package com.buildgraph.prototype.quote;

import com.buildgraph.prototype.build.BuildEvaluationService;
import com.buildgraph.prototype.part.query.PartQuery;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.EvaluationCandidate;
import com.buildgraph.prototype.quote.QuoteDraftHistoryStore.SnapshotItem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class QuoteDraftHistoryEvaluationWorker {
    private static final Logger log = LoggerFactory.getLogger(QuoteDraftHistoryEvaluationWorker.class);

    private final QuoteDraftHistoryStore historyStore;
    private final PartQuery partQuery;
    private final BuildEvaluationService buildEvaluationService;
    private final int batchSize;
    private final int maxDrainBatches;
    private final int maxAttempts;
    private final long retryBaseDelayMs;
    private final long retryMaxDelayMs;

    @Autowired
    public QuoteDraftHistoryEvaluationWorker(
            QuoteDraftHistoryStore historyStore,
            PartQuery partQuery,
            BuildEvaluationService buildEvaluationService,
            @Value("${buildgraph.quote-draft-history.evaluation.batch-size:8}") int batchSize,
            @Value("${buildgraph.quote-draft-history.evaluation.max-drain-batches:8}") int maxDrainBatches,
            @Value("${buildgraph.quote-draft-history.evaluation.retry-max-attempts:3}") int maxAttempts,
            @Value("${buildgraph.quote-draft-history.evaluation.retry-base-delay-ms:500}") long retryBaseDelayMs,
            @Value("${buildgraph.quote-draft-history.evaluation.retry-max-delay-ms:5000}") long retryMaxDelayMs
    ) {
        this.historyStore = historyStore;
        this.partQuery = partQuery;
        this.buildEvaluationService = buildEvaluationService;
        this.batchSize = Math.max(1, Math.min(20, batchSize));
        this.maxDrainBatches = Math.max(1, Math.min(100, maxDrainBatches));
        this.maxAttempts = Math.max(1, Math.min(10, maxAttempts));
        this.retryBaseDelayMs = Math.max(0, retryBaseDelayMs);
        this.retryMaxDelayMs = Math.max(this.retryBaseDelayMs, retryMaxDelayMs);
    }

    QuoteDraftHistoryEvaluationWorker(
            QuoteDraftHistoryStore historyStore,
            PartQuery partQuery,
            BuildEvaluationService buildEvaluationService,
            int batchSize
    ) {
        this(historyStore, partQuery, buildEvaluationService, batchSize, 8, 3, 500, 5000);
    }

    @Scheduled(
            fixedDelayString = "${buildgraph.quote-draft-history.evaluation.fixed-delay-ms:750}",
            initialDelayString = "${buildgraph.quote-draft-history.evaluation.initial-delay-ms:1000}"
    )
    public void evaluatePending() {
        drainReadyEvaluations();
    }

    int drainReadyEvaluations() {
        int processed = 0;
        for (int batch = 0; batch < maxDrainBatches; batch += 1) {
            List<EvaluationCandidate> candidates = historyStore.claimPendingEvaluations(batchSize);
            for (EvaluationCandidate candidate : candidates) {
                evaluate(candidate);
            }
            processed += candidates.size();
            if (candidates.size() < batchSize) {
                break;
            }
        }
        return processed;
    }

    Long nextEvaluationDelayMs() {
        return historyStore.nextEvaluationDelayMs();
    }

    void evaluate(EvaluationCandidate candidate) {
        try {
            Map<String, Integer> quantities = new LinkedHashMap<>();
            candidate.snapshot().items().forEach(item -> quantities.put(item.partId(), item.quantity()));
            List<String> ids = candidate.snapshot().items().stream().map(SnapshotItem::partId).toList();
            List<ToolBuildPart> parts = partQuery.partsForPublicIdQuantities(ids, quantities);
            if (parts.size() < candidate.snapshot().items().size()) {
                if (!historyStore.unavailableItems(candidate.snapshot()).isEmpty()) {
                    historyStore.markEvaluationUnavailable(candidate, "PART_UNAVAILABLE");
                } else {
                    scheduleRetry(candidate, "PART_LOOKUP_INCOMPLETE");
                }
                return;
            }

            BuildEvaluationService.BuildEvaluation evaluation = buildEvaluationService.evaluate(parts, null, null, null);
            Integer score = integer(evaluation.compositeScore().get("score"));
            Integer maxScore = integer(evaluation.compositeScore().get("maxScore"));
            if (score == null || maxScore == null) {
                scheduleRetry(candidate, "SCORE_UNAVAILABLE");
                return;
            }

            boolean hasToolFail = evaluation.toolResults().stream()
                    .anyMatch(result -> "FAIL".equalsIgnoreCase(text(result.get("status"))));
            boolean invalid = score <= 0 || hasToolFail;
            List<String> issueCodes = issueCodes(evaluation, invalid);
            String issueSignature = invalid ? signature(issueCodes) : null;
            historyStore.completeEvaluation(
                    candidate,
                    invalid ? "INVALID" : "VALID",
                    score,
                    maxScore,
                    issueSignature,
                    issueCodes
            );
        } catch (RuntimeException exception) {
            log.warn(
                    "견적 변경 기록 평가를 재시도합니다. historyId={}, attempt={}",
                    candidate.id(),
                    candidate.attempt(),
                    exception
            );
            scheduleRetry(candidate, "EVALUATION_ERROR");
        }
    }

    private void scheduleRetry(EvaluationCandidate candidate, String errorCode) {
        long delayMs = retryDelayMs(candidate.attempt());
        boolean scheduled = historyStore.scheduleEvaluationRetry(
                candidate,
                errorCode,
                maxAttempts,
                delayMs
        );
        if (!scheduled && candidate.attempt() >= maxAttempts) {
            log.warn(
                    "견적 변경 기록 평가를 {}회 시도한 뒤 중단했습니다. historyId={}, errorCode={}",
                    maxAttempts,
                    candidate.id(),
                    errorCode
            );
        }
    }

    private long retryDelayMs(int attempt) {
        int exponent = Math.max(0, Math.min(20, attempt - 1));
        long multiplier = 1L << exponent;
        long calculated;
        try {
            calculated = Math.multiplyExact(retryBaseDelayMs, multiplier);
        } catch (ArithmeticException ignored) {
            calculated = retryMaxDelayMs;
        }
        return Math.min(retryMaxDelayMs, calculated);
    }

    private static List<String> issueCodes(
            BuildEvaluationService.BuildEvaluation evaluation,
            boolean invalid
    ) {
        Set<String> codes = new LinkedHashSet<>();
        for (Map<String, Object> caution : objectMaps(evaluation.buildAssessment().get("cautions"))) {
            String code = text(caution.get("code"));
            String severity = text(caution.get("severity"));
            if (code != null && (!invalid || "FAIL".equalsIgnoreCase(severity))) {
                codes.add(code.toUpperCase(Locale.ROOT));
            }
        }
        evaluation.toolResults().stream()
                .filter(result -> "FAIL".equalsIgnoreCase(text(result.get("status"))))
                .map(result -> text(result.get("tool")))
                .filter(java.util.Objects::nonNull)
                .map(tool -> "TOOL_FAIL_" + tool.toUpperCase(Locale.ROOT))
                .forEach(codes::add);
        if (invalid && codes.isEmpty()) {
            codes.add("ZERO_SCORE");
        }
        return new ArrayList<>(codes).stream().sorted().toList();
    }

    private static String signature(List<String> codes) {
        String source = String.join("|", codes);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static Integer integer(Object value) {
        if (value instanceof Number number) return number.intValue();
        if (value == null) return null;
        try {
            return Integer.valueOf(value.toString());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String text(Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objectMaps(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        return list.stream().filter(Map.class::isInstance).map(item -> (Map<String, Object>) item).toList();
    }
}

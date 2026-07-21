package com.buildgraph.prototype.quote;

import com.buildgraph.prototype.common.ApiException;
import com.buildgraph.prototype.common.DbValueMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class QuoteDraftHistoryStore {
    private static final int VALID_HISTORY_LIMIT = 20;
    private static final int INVALID_HISTORY_LIMIT = 5;
    private static final int UNEVALUATED_HISTORY_LIMIT = 20;
    private static final int HISTORY_LIST_LIMIT = VALID_HISTORY_LIMIT + INVALID_HISTORY_LIMIT + UNEVALUATED_HISTORY_LIMIT;
    private static final List<String> CATEGORY_ORDER = List.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
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
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public QuoteDraftHistoryStore(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            ApplicationEventPublisher eventPublisher
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    public Map<String, Object> activeDraft(long userId) {
        return activeDraft(userId, false);
    }

    public Map<String, Object> lockActiveDraft(long userId) {
        return activeDraft(userId, true);
    }

    public void lockDraft(long draftId) {
        jdbcTemplate.queryForObject(
                "SELECT id FROM quote_drafts WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                Long.class,
                draftId
        );
    }

    public boolean captureBeforeMutation(
            long draftId,
            String requestedChangeGroup,
            String actionType,
            List<String> relatedCategories,
            Map<String, Object> metadata
    ) {
        UUID changeGroup = changeGroup(requestedChangeGroup);
        HistoryGroup existingGroup = historyGroup(draftId, changeGroup);
        if (existingGroup != null) {
            mergeGroupCategories(draftId, changeGroup, existingGroup, relatedCategories);
            return false;
        }

        Snapshot snapshot = snapshot(draftId);
        if (snapshot.items().isEmpty()) {
            return false;
        }

        Map<String, Object> safeMetadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        if ("QUANTITY_CHANGE".equals(actionType) && recentQuantityHistoryExists(draftId, safeMetadata.get("partId"))) {
            return false;
        }

        String fingerprint = fingerprint(snapshot);
        if (fingerprint.equals(latestFingerprint(draftId))) {
            return false;
        }

        List<String> categories = normalizeCategories(relatedCategories);
        int inserted = jdbcTemplate.update("""
                INSERT INTO quote_draft_history_entries (
                    quote_draft_id, change_group_id, action_type, action_label,
                    related_categories, metadata, snapshot_payload, snapshot_fingerprint
                ) VALUES (?, ?, ?::varchar, ?, ?::jsonb, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (quote_draft_id, change_group_id) DO NOTHING
                """,
                draftId,
                changeGroup,
                actionType,
                actionLabel(actionType, categories),
                json(categories),
                json(safeMetadata),
                json(snapshot),
                fingerprint
        );
        if (inserted == 0) {
            return false;
        }
        prune(draftId);
        eventPublisher.publishEvent(new QuoteDraftHistoryEvaluationRequested(draftId));
        return true;
    }

    public List<HistoryEntry> entries(long draftId) {
        prune(draftId);
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               action_type,
                               action_label,
                               related_categories,
                               metadata,
                               snapshot_payload,
                               snapshot_fingerprint,
                               evaluation_status,
                               evaluation_score,
                               evaluation_max_score,
                               evaluation_issue_signature,
                               evaluation_issue_codes,
                               evaluated_at,
                               created_at,
                               expires_at
                        FROM quote_draft_history_entries
                        WHERE quote_draft_id = ?
                          AND expires_at > now()
                        ORDER BY created_at DESC, id DESC
                        LIMIT ?
                        """, draftId, HISTORY_LIST_LIMIT)
                .stream()
                .map(this::historyEntry)
                .toList();
    }

    public HistoryEntry requireEntry(long draftId, String publicId) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               action_type,
                               action_label,
                               related_categories,
                               metadata,
                               snapshot_payload,
                               snapshot_fingerprint,
                               evaluation_status,
                               evaluation_score,
                               evaluation_max_score,
                               evaluation_issue_signature,
                               evaluation_issue_codes,
                               evaluated_at,
                               created_at,
                               expires_at
                        FROM quote_draft_history_entries
                        WHERE quote_draft_id = ?
                          AND public_id = ?::uuid
                          AND expires_at > now()
                        """, draftId, publicId)
                .stream()
                .findFirst()
                .map(this::historyEntry)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "견적 변경 기록을 찾을 수 없습니다."));
    }

    public List<EvaluationCandidate> claimPendingEvaluations(int requestedLimit) {
        int limit = Math.max(1, Math.min(20, requestedLimit));
        return jdbcTemplate.queryForList("""
                        WITH candidates AS (
                            SELECT id
                            FROM quote_draft_history_entries
                            WHERE expires_at > now()
                              AND (
                                (
                                  evaluation_status = 'PENDING'
                                  AND (
                                    evaluation_next_attempt_at IS NULL
                                    OR evaluation_next_attempt_at <= now()
                                  )
                                )
                                OR (
                                  evaluation_status = 'RUNNING'
                                  AND coalesce(evaluation_started_at, created_at) < now() - interval '5 minutes'
                                )
                              )
                            ORDER BY created_at, id
                            FOR UPDATE SKIP LOCKED
                            LIMIT ?
                        )
                        UPDATE quote_draft_history_entries history
                        SET evaluation_status = 'RUNNING',
                            evaluation_started_at = now(),
                            evaluation_attempts = history.evaluation_attempts + 1,
                            evaluation_next_attempt_at = NULL
                        FROM candidates
                        WHERE history.id = candidates.id
                        RETURNING history.public_id::text AS id,
                                  history.quote_draft_id,
                                  history.snapshot_payload,
                                  history.evaluation_attempts
                        """, limit)
                .stream()
                .map(row -> new EvaluationCandidate(
                        DbValueMapper.string(row, "id"),
                        ((Number) row.get("quote_draft_id")).longValue(),
                        snapshotValue(row.get("snapshot_payload")),
                        DbValueMapper.integer(row, "evaluation_attempts")
                ))
                .toList();
    }

    public void completeEvaluation(
            EvaluationCandidate candidate,
            String status,
            Integer score,
            Integer maxScore,
            String issueSignature,
            List<String> issueCodes
    ) {
        jdbcTemplate.update("""
                UPDATE quote_draft_history_entries
                SET evaluation_status = ?::varchar,
                    evaluation_score = ?,
                    evaluation_max_score = ?,
                    evaluation_issue_signature = ?,
                    evaluation_issue_codes = ?::jsonb,
                    evaluation_next_attempt_at = NULL,
                    evaluation_last_error_code = NULL,
                    evaluation_last_error_at = NULL,
                    evaluated_at = now()
                WHERE public_id = ?::uuid
                  AND evaluation_status = 'RUNNING'
                """,
                status,
                score,
                maxScore,
                issueSignature,
                json(issueCodes == null ? List.of() : issueCodes),
                candidate.id()
        );
        compactInvalidRuns(candidate.draftId());
        prune(candidate.draftId());
    }

    public void markEvaluationUnavailable(EvaluationCandidate candidate, String errorCode) {
        jdbcTemplate.update("""
                UPDATE quote_draft_history_entries
                SET evaluation_status = 'UNAVAILABLE',
                    evaluation_score = NULL,
                    evaluation_max_score = NULL,
                    evaluation_issue_signature = NULL,
                    evaluation_issue_codes = '[]'::jsonb,
                    evaluation_next_attempt_at = NULL,
                    evaluation_last_error_code = ?::varchar,
                    evaluation_last_error_at = now(),
                    evaluated_at = now()
                WHERE public_id = ?::uuid
                  AND evaluation_status = 'RUNNING'
                """, normalizedErrorCode(errorCode), candidate.id());
        prune(candidate.draftId());
    }

    public boolean scheduleEvaluationRetry(
            EvaluationCandidate candidate,
            String errorCode,
            int maxAttempts,
            long delayMs
    ) {
        if (candidate.attempt() >= Math.max(1, maxAttempts)) {
            markEvaluationUnavailable(candidate, errorCode);
            return false;
        }
        int updated = jdbcTemplate.update("""
                UPDATE quote_draft_history_entries
                SET evaluation_status = 'PENDING',
                    evaluation_started_at = NULL,
                    evaluation_next_attempt_at = now() + (? * interval '1 millisecond'),
                    evaluation_last_error_code = ?::varchar,
                    evaluation_last_error_at = now()
                WHERE public_id = ?::uuid
                  AND evaluation_status = 'RUNNING'
                """, Math.max(0, delayMs), normalizedErrorCode(errorCode), candidate.id());
        return updated > 0;
    }

    public Long nextEvaluationDelayMs() {
        return jdbcTemplate.queryForObject("""
                SELECT CASE
                         WHEN count(*) = 0 THEN NULL
                         ELSE GREATEST(
                           0,
                           floor(extract(epoch FROM (
                             min(CASE
                               WHEN evaluation_status = 'PENDING'
                                 THEN coalesce(evaluation_next_attempt_at, now())
                               ELSE coalesce(evaluation_started_at, created_at) + interval '5 minutes'
                             END) - now()
                           )) * 1000)::bigint
                         )
                       END AS delay_ms
                FROM quote_draft_history_entries
                WHERE expires_at > now()
                  AND evaluation_status IN ('PENDING', 'RUNNING')
                """, Long.class);
    }

    public Snapshot snapshot(long draftId) {
        List<SnapshotItem> items = jdbcTemplate.queryForList("""
                        SELECT p.public_id::text AS part_id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               qdi.quantity,
                               p.price AS unit_price_at_add
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                        ORDER BY CASE p.category
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
                                 qdi.id
                        """, draftId)
                .stream()
                .map(row -> new SnapshotItem(
                        DbValueMapper.string(row, "part_id"),
                        DbValueMapper.string(row, "category"),
                        DbValueMapper.string(row, "name"),
                        DbValueMapper.string(row, "manufacturer"),
                        DbValueMapper.integer(row, "quantity"),
                        DbValueMapper.integer(row, "unit_price_at_add")
                ))
                .toList();
        int totalPrice = items.stream().mapToInt(SnapshotItem::lineTotal).sum();
        int itemCount = items.stream().mapToInt(SnapshotItem::quantity).sum();
        return new Snapshot(items, totalPrice, itemCount);
    }

    public List<UnavailableItem> unavailableItems(Snapshot snapshot) {
        if (snapshot.items().isEmpty()) {
            return List.of();
        }
        List<String> ids = snapshot.items().stream().map(SnapshotItem::partId).distinct().toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?::uuid"));
        Set<String> activeIds = new LinkedHashSet<>(jdbcTemplate.queryForList("""
                SELECT public_id::text
                FROM parts
                WHERE public_id IN (%s)
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                """.formatted(placeholders), String.class, ids.toArray()));
        return snapshot.items().stream()
                .filter(item -> !activeIds.contains(item.partId()))
                .map(item -> new UnavailableItem(item.partId(), item.category(), item.name()))
                .toList();
    }

    public void replaceDraft(long draftId, Snapshot snapshot) {
        Map<String, Long> internalIds = activePartInternalIds(snapshot);
        jdbcTemplate.update("""
                UPDATE quote_draft_items
                SET deleted_at = now(), updated_at = now()
                WHERE quote_draft_id = ? AND deleted_at IS NULL
                """, draftId);
        jdbcTemplate.batchUpdate("""
                        INSERT INTO quote_draft_items (quote_draft_id, part_id, category, quantity, unit_price_at_add)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                snapshot.items().stream()
                        .map(item -> new Object[]{
                                draftId,
                                internalIds.get(item.partId()),
                                item.category(),
                                item.quantity(),
                                item.unitPriceAtAdd()
                        })
                        .toList());
        jdbcTemplate.update("UPDATE quote_drafts SET updated_at = now() WHERE id = ?", draftId);
    }

    public static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private Map<String, Object> activeDraft(long userId, boolean lock) {
        String suffix = lock ? " FOR UPDATE" : "";
        return jdbcTemplate.queryForList("""
                        SELECT id AS internal_id,
                               public_id::text AS id,
                               status,
                               name,
                               created_at,
                               updated_at
                        FROM quote_drafts
                        WHERE user_id = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """ + suffix, userId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private HistoryGroup historyGroup(long draftId, UUID changeGroup) {
        return jdbcTemplate.queryForList("""
                SELECT action_type, related_categories
                FROM quote_draft_history_entries
                WHERE quote_draft_id = ? AND change_group_id = ?
                """, draftId, changeGroup)
                .stream()
                .findFirst()
                .map(row -> new HistoryGroup(
                        DbValueMapper.string(row, "action_type"),
                        stringList(DbValueMapper.json(row, "related_categories", List.of()))
                ))
                .orElse(null);
    }

    private void mergeGroupCategories(
            long draftId,
            UUID changeGroup,
            HistoryGroup existingGroup,
            List<String> requestedCategories
    ) {
        List<String> merged = normalizeCategories(java.util.stream.Stream.concat(
                        existingGroup.categories().stream(),
                        requestedCategories == null ? java.util.stream.Stream.empty() : requestedCategories.stream()
                )
                .toList());
        if (merged.equals(existingGroup.categories())) {
            return;
        }
        jdbcTemplate.update("""
                UPDATE quote_draft_history_entries
                SET related_categories = ?::jsonb,
                    action_label = ?
                WHERE quote_draft_id = ? AND change_group_id = ?
                """,
                json(merged),
                actionLabel(existingGroup.actionType(), merged),
                draftId,
                changeGroup
        );
    }

    private boolean recentQuantityHistoryExists(long draftId, Object partId) {
        if (partId == null) {
            return false;
        }
        Boolean recent = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM (
                        SELECT action_type, metadata, created_at
                        FROM quote_draft_history_entries
                        WHERE quote_draft_id = ?
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                    ) latest
                    WHERE latest.action_type = 'QUANTITY_CHANGE'
                      AND latest.metadata ->> 'partId' = ?
                      AND latest.created_at >= now() - interval '10 seconds'
                )
                """, Boolean.class, draftId, partId.toString());
        return Boolean.TRUE.equals(recent);
    }

    private String latestFingerprint(long draftId) {
        return jdbcTemplate.queryForList("""
                        SELECT snapshot_fingerprint
                        FROM quote_draft_history_entries
                        WHERE quote_draft_id = ? AND expires_at > now()
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """, String.class, draftId)
                .stream()
                .findFirst()
                .orElse(null);
    }

    private Map<String, Long> activePartInternalIds(Snapshot snapshot) {
        if (snapshot.items().isEmpty()) {
            return Map.of();
        }
        List<String> ids = snapshot.items().stream().map(SnapshotItem::partId).distinct().toList();
        String placeholders = String.join(", ", java.util.Collections.nCopies(ids.size(), "?::uuid"));
        Map<String, Long> rows = new LinkedHashMap<>();
        jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id, id AS internal_id
                        FROM parts
                        WHERE public_id IN (%s)
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                        """.formatted(placeholders), ids.toArray())
                .forEach(row -> rows.put(DbValueMapper.string(row, "id"), ((Number) row.get("internal_id")).longValue()));
        return rows;
    }

    private void prune(long draftId) {
        jdbcTemplate.update("DELETE FROM quote_draft_history_entries WHERE expires_at <= now()");
        jdbcTemplate.update("""
                WITH ranked AS (
                    SELECT id,
                           CASE
                             WHEN evaluation_status = 'VALID' THEN 'VALID'
                             WHEN evaluation_status = 'INVALID' THEN 'INVALID'
                             ELSE 'UNEVALUATED'
                           END AS bucket,
                           row_number() OVER (
                             PARTITION BY CASE
                               WHEN evaluation_status = 'VALID' THEN 'VALID'
                               WHEN evaluation_status = 'INVALID' THEN 'INVALID'
                               ELSE 'UNEVALUATED'
                             END
                             ORDER BY created_at DESC, id DESC
                           ) AS row_number
                    FROM quote_draft_history_entries
                    WHERE quote_draft_id = ?
                )
                DELETE FROM quote_draft_history_entries history
                USING ranked
                WHERE history.id = ranked.id
                  AND (
                    (ranked.bucket = 'VALID' AND ranked.row_number > ?)
                    OR (ranked.bucket = 'INVALID' AND ranked.row_number > ?)
                    OR (ranked.bucket = 'UNEVALUATED' AND ranked.row_number > ?)
                  )
                """, draftId, VALID_HISTORY_LIMIT, INVALID_HISTORY_LIMIT, UNEVALUATED_HISTORY_LIMIT);
    }

    private void compactInvalidRuns(long draftId) {
        jdbcTemplate.update("""
                WITH sequenced AS (
                    SELECT id,
                           evaluation_status,
                           evaluation_issue_signature,
                           created_at,
                           lag(evaluation_status) OVER (ORDER BY created_at, id) AS previous_status,
                           lag(evaluation_issue_signature) OVER (ORDER BY created_at, id) AS previous_signature
                    FROM quote_draft_history_entries
                    WHERE quote_draft_id = ?
                      AND expires_at > now()
                ), boundaries AS (
                    SELECT id,
                           evaluation_status,
                           evaluation_issue_signature,
                           created_at,
                           CASE
                             WHEN evaluation_status = 'INVALID'
                              AND previous_status = 'INVALID'
                              AND evaluation_issue_signature IS NOT DISTINCT FROM previous_signature
                             THEN 0 ELSE 1
                           END AS starts_new_run
                    FROM sequenced
                ), grouped AS (
                    SELECT id,
                           evaluation_status,
                           created_at,
                           sum(starts_new_run) OVER (ORDER BY created_at, id) AS run_id
                    FROM boundaries
                ), duplicates AS (
                    SELECT id,
                           row_number() OVER (
                             PARTITION BY run_id
                             ORDER BY created_at DESC, id DESC
                           ) AS row_number
                    FROM grouped
                    WHERE evaluation_status = 'INVALID'
                )
                DELETE FROM quote_draft_history_entries history
                USING duplicates
                WHERE history.id = duplicates.id
                  AND duplicates.row_number > 1
                """, draftId);
    }

    private HistoryEntry historyEntry(Map<String, Object> row) {
        return new HistoryEntry(
                DbValueMapper.string(row, "id"),
                DbValueMapper.string(row, "action_type"),
                DbValueMapper.string(row, "action_label"),
                stringList(DbValueMapper.json(row, "related_categories", List.of())),
                objectMap(DbValueMapper.json(row, "metadata", Map.of())),
                snapshotValue(row.get("snapshot_payload")),
                DbValueMapper.string(row, "snapshot_fingerprint"),
                DbValueMapper.string(row, "evaluation_status"),
                DbValueMapper.integer(row, "evaluation_score"),
                DbValueMapper.integer(row, "evaluation_max_score"),
                DbValueMapper.string(row, "evaluation_issue_signature"),
                stringList(DbValueMapper.json(row, "evaluation_issue_codes", List.of())),
                offsetDateTime(row.get("evaluated_at")),
                offsetDateTime(row.get("created_at")),
                offsetDateTime(row.get("expires_at"))
        );
    }

    private Snapshot snapshotValue(Object value) {
        try {
            return objectMapper.readValue(value.toString(), Snapshot.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("견적 변경 기록을 읽을 수 없습니다.", exception);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("견적 변경 기록을 저장할 수 없습니다.", exception);
        }
    }

    private static String fingerprint(Snapshot snapshot) {
        String source = snapshot.items().stream()
                .map(item -> String.join(":",
                        item.category(), item.partId(), String.valueOf(item.quantity()), String.valueOf(item.unitPriceAtAdd())))
                .reduce((left, right) -> left + "|" + right)
                .orElse("");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
        }
    }

    private static UUID changeGroup(String value) {
        if (value == null || value.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "X-Quote-Draft-Change-Group은 UUID 형식이어야 합니다.");
        }
    }

    private static List<String> normalizeCategories(List<String> categories) {
        Set<String> normalized = new LinkedHashSet<>(categories == null ? List.of() : categories);
        return CATEGORY_ORDER.stream().filter(normalized::contains).toList();
    }

    private static String actionLabel(String actionType, List<String> categories) {
        String category = categories.isEmpty()
                ? "견적"
                : categories.size() > 1 ? "여러 부품" : categoryLabel(categories.get(0));
        return switch (actionType) {
            case "ITEM_ADD" -> category + " 추가 전";
            case "ITEM_REPLACE" -> category + " 교체 전";
            case "ITEM_REMOVE" -> category + " 제거 전";
            case "QUANTITY_CHANGE" -> category + " 수량 변경 전";
            case "AI_BUILD_APPLY" -> "AI 견적 적용 전";
            case "RESTORE" -> "기록 복원 전";
            default -> "견적 변경 전";
        };
    }

    private static String normalizedErrorCode(String value) {
        String normalized = value == null ? "EVALUATION_ERROR" : value.trim().toUpperCase(java.util.Locale.ROOT);
        if (normalized.isBlank()) {
            return "EVALUATION_ERROR";
        }
        return normalized.substring(0, Math.min(80, normalized.length()));
    }

    static OffsetDateTime offsetDateTime(Object value) {
        if (value instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant().atOffset(ZoneOffset.UTC);
        }
        if (value instanceof Instant instant) {
            return instant.atOffset(ZoneOffset.UTC);
        }
        if (value instanceof ZonedDateTime zonedDateTime) {
            return zonedDateTime.toOffsetDateTime();
        }
        if (value instanceof LocalDateTime localDateTime) {
            return localDateTime.atOffset(ZoneOffset.UTC);
        }
        if (value == null) {
            return null;
        }
        String normalized = value.toString().trim().replace(' ', 'T');
        try {
            return OffsetDateTime.parse(normalized);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(normalized).atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("견적 변경 기록 시각 형식이 올바르지 않습니다: " + value, invalid);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().map(String::valueOf).toList();
    }

    public record Snapshot(List<SnapshotItem> items, int totalPrice, int itemCount) {
        public Snapshot {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    public record SnapshotItem(
            String partId,
            String category,
            String name,
            String manufacturer,
            int quantity,
            int unitPriceAtAdd
    ) {
        public int lineTotal() {
            return Math.max(0, unitPriceAtAdd) * Math.max(1, quantity);
        }
    }

    public record HistoryEntry(
            String id,
            String actionType,
            String actionLabel,
            List<String> relatedCategories,
            Map<String, Object> metadata,
            Snapshot snapshot,
            String fingerprint,
            String evaluationStatus,
            Integer evaluationScore,
            Integer evaluationMaxScore,
            String evaluationIssueSignature,
            List<String> evaluationIssueCodes,
            OffsetDateTime evaluatedAt,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt
    ) {
    }

    public record EvaluationCandidate(String id, long draftId, Snapshot snapshot, int attempt) {
    }

    public record UnavailableItem(String partId, String category, String name) {
    }

    private record HistoryGroup(String actionType, List<String> categories) {
    }
}

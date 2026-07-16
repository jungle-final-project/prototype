package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.part.tool.ToolApplicabilityPolicy;
import com.buildgraph.prototype.part.tool.ToolBuildPart;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class BuildEvaluationService {
    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;
    private final BuildCompositeScoreService buildCompositeScoreService;
    private final BuildScoreAdviceService buildScoreAdviceService;

    public BuildEvaluationService(
            JdbcTemplate jdbcTemplate,
            ToolCheckService toolCheckService,
            BuildCompositeScoreService buildCompositeScoreService,
            BuildScoreAdviceService buildScoreAdviceService
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
        this.buildCompositeScoreService = buildCompositeScoreService;
        this.buildScoreAdviceService = buildScoreAdviceService;
    }

    public BuildEvaluation evaluate(
            List<ToolBuildPart> parts,
            Integer requestedBudget,
            String focusCategory,
            String focusTool
    ) {
        List<ToolBuildPart> safeParts = parts == null ? List.of() : parts;
        int requestedBudgetWon = requestedBudget != null && requestedBudget > 0
                ? requestedBudget
                : total(safeParts);
        List<Map<String, Object>> rawToolResults = safeParts.isEmpty()
                ? List.of()
                : toolCheckService.checkBuild(safeParts, requestedBudgetWon);
        return evaluateSnapshot(safeParts, rawToolResults, requestedBudget, focusCategory, focusTool);
    }

    /**
     * 이미 Tool 검증을 마친 추천 카드도 그래프/현재 견적과 동일한 점수·조언 정책으로 평가한다.
     * Tool을 다시 호출하지 않아 추천 응답 지연을 늘리지 않는다.
     */
    public BuildEvaluation evaluateSnapshot(
            List<ToolBuildPart> parts,
            List<Map<String, Object>> rawToolResults,
            Integer requestedBudget,
            String focusCategory,
            String focusTool
    ) {
        List<ToolBuildPart> safeParts = parts == null ? List.of() : parts;
        int totalPrice = total(safeParts);
        int budgetWon = requestedBudget != null && requestedBudget > 0 ? requestedBudget : totalPrice;
        List<Map<String, Object>> toolResults = safeParts.isEmpty()
                ? List.of()
                : ToolApplicabilityPolicy.applicableToolResults(
                        rawToolResults == null ? List.of() : rawToolResults,
                        safeParts
                );
        Map<String, Object> compositeScore = buildCompositeScoreService.score(
                safeParts,
                toolResults,
                budgetWon,
                totalPrice
        );
        Map<String, Object> buildAssessment = buildScoreAdviceService.assess(
                safeParts,
                toolResults,
                compositeScore,
                focusCategory,
                focusTool
        );
        return new BuildEvaluation(safeParts, totalPrice, budgetWon, toolResults, compositeScore, buildAssessment);
    }

    public BuildEvaluation evaluateCurrentDraft(
            long userInternalId,
            Integer requestedBudget,
            String focusCategory,
            String focusTool
    ) {
        return evaluate(currentDraftParts(userInternalId), requestedBudget, focusCategory, focusTool);
    }

    private List<ToolBuildPart> currentDraftParts(long userInternalId) {
        List<Map<String, Object>> drafts = jdbcTemplate.queryForList("""
                SELECT id AS internal_id
                FROM quote_drafts
                WHERE user_id = ?
                  AND status = 'ACTIVE'
                  AND deleted_at IS NULL
                ORDER BY updated_at DESC, id DESC
                LIMIT 1
                """, userInternalId);
        if (drafts.isEmpty()) {
            return List.of();
        }
        Long draftId = longValue(drafts.get(0).get("internal_id"));
        return jdbcTemplate.queryForList("""
                        SELECT p.id AS internal_id,
                               p.public_id::text AS part_id,
                               p.category,
                               p.name,
                               p.manufacturer,
                               p.price AS current_price,
                               qdi.quantity,
                               p.attributes
                        FROM quote_draft_items qdi
                        JOIN parts p ON p.id = qdi.part_id
                        WHERE qdi.quote_draft_id = ?
                          AND qdi.deleted_at IS NULL
                          AND p.deleted_at IS NULL
                        ORDER BY qdi.id
                        """, draftId)
                .stream()
                .map(row -> new ToolBuildPart(
                        longValue(row.get("internal_id")),
                        DbValueMapper.string(row, "part_id"),
                        DbValueMapper.string(row, "category"),
                        DbValueMapper.string(row, "name"),
                        DbValueMapper.string(row, "manufacturer"),
                        numberValue(row.get("current_price")),
                        objectMap(row.get("attributes")),
                        numberValue(row.get("quantity"))
                ))
                .toList();
    }

    private static int total(List<ToolBuildPart> parts) {
        return parts.stream()
                .mapToInt(part -> Math.max(0, part.price() == null ? 0 : part.price()) * part.effectiveQuantity())
                .sum();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        if (value == null) {
            return Map.of();
        }
        return (Map<String, Object>) DbValueMapper.json(Map.of("json", value), "json", Map.of());
    }

    private static int numberValue(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    public record BuildEvaluation(
            List<ToolBuildPart> parts,
            int totalPrice,
            int budgetWon,
            List<Map<String, Object>> toolResults,
            Map<String, Object> compositeScore,
            Map<String, Object> buildAssessment
    ) {
    }
}

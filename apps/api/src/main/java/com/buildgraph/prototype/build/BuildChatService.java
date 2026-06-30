package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.part.ToolBuildPart;
import com.buildgraph.prototype.part.ToolCheckService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuildChatService {
    private static final Pattern BUDGET_MANWON = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*(?:만원|만)");
    private static final Pattern BUDGET_WON = Pattern.compile("(\\d{6,})\\s*원?");
    private static final List<String> BUILD_CATEGORIES = List.of("CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER");
    private static final List<Tier> TIERS = List.of(
            new Tier("budget", "가성비", "실속형", 0.86),
            new Tier("balanced", "균형", "균형형", 0.94),
            new Tier("performance", "고성능", "성능형", 0.99)
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
    private static final Map<String, Double> CATEGORY_RATIOS = Map.of(
            "CPU", 0.17,
            "MOTHERBOARD", 0.11,
            "RAM", 0.07,
            "GPU", 0.39,
            "STORAGE", 0.07,
            "PSU", 0.08,
            "CASE", 0.06,
            "COOLER", 0.05
    );

    private final JdbcTemplate jdbcTemplate;
    private final ToolCheckService toolCheckService;

    public BuildChatService(JdbcTemplate jdbcTemplate, ToolCheckService toolCheckService) {
        this.jdbcTemplate = jdbcTemplate;
        this.toolCheckService = toolCheckService;
    }

    public Map<String, Object> chat(Map<String, Object> request) {
        Map<String, Object> body = request == null ? Map.of() : request;
        String message = text(body.get("message"));
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "message는 필수입니다.");
        }

        Integer budgetWon = parseBudgetWon(message);
        if (budgetWon != null) {
            List<Map<String, Object>> builds = buildsForBudget(budgetWon, List.of());
            return MockData.map(
                    "answerType", "BUDGET",
                    "message", formatBudgetLabel(budgetWon) + " 예산 기준으로 실속형, 균형형, 성능형 3개 조합을 DB/룰 기반으로 계산했습니다.",
                    "builds", builds,
                    "partRecommendation", null,
                    "warnings", buildWarnings(builds)
            );
        }

        String category = detectPartCategory(message);
        if (category != null) {
            List<String> warnings = new ArrayList<>();
            List<PartCandidate> options = partRecommendationOptions(category);
            List<AiBuildCandidate> baseBuilds = currentBuilds(body.get("currentBuilds"), warnings);
            List<Map<String, Object>> updatedBuilds = baseBuilds.isEmpty()
                    ? List.of()
                    : applyPartOptions(baseBuilds, category, options);
            Map<String, Object> partRecommendation = MockData.map(
                    "category", category,
                    "label", categoryLabel(category),
                    "intro", categoryLabel(category) + " 후보를 실제 부품 DB 현재가 기준으로 비교했습니다.",
                    "options", options.stream()
                            .map(option -> partItem(option, categoryLabel(category) + " DB 추천 후보"))
                            .toList()
            );
            warnings.addAll(buildWarnings(updatedBuilds));
            return MockData.map(
                    "answerType", "PART",
                    "message", baseBuilds.isEmpty()
                            ? categoryLabel(category) + " 추천 후보 3개를 실제 부품 DB 현재가 기준으로 정리했습니다."
                            : categoryLabel(category) + " 추천 후보 3개를 정리했고 최신 AI 추천 컴퓨터 3개에도 반영했습니다.",
                    "builds", updatedBuilds,
                    "partRecommendation", partRecommendation,
                    "warnings", distinct(warnings)
            );
        }

        return MockData.map(
                "answerType", "GENERAL",
                "message", "예산은 “200만원 PC 추천”, 부품은 “GPU 추천해줘”처럼 물어보면 DB/룰 기반 추천을 계산합니다.",
                "builds", currentBuilds(body.get("currentBuilds"), new ArrayList<>()).stream().map(this::buildMap).toList(),
                "partRecommendation", null,
                "warnings", List.of()
        );
    }

    static Integer parseBudgetWon(String message) {
        if (message == null) {
            return null;
        }
        String normalized = message.replace(",", "").toLowerCase(Locale.ROOT);
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

    static String detectPartCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        List<CategoryKeywords> checks = List.of(
                new CategoryKeywords("MOTHERBOARD", List.of("메인보드", "마더보드", "보드", "motherboard")),
                new CategoryKeywords("COOLER", List.of("쿨러", "cooler", "수랭", "공랭")),
                new CategoryKeywords("STORAGE", List.of("ssd", "스토리지", "저장장치", "저장 공간", "nvme")),
                new CategoryKeywords("PSU", List.of("파워", "psu", "전원공급", "전원 공급")),
                new CategoryKeywords("CASE", List.of("케이스", "case")),
                new CategoryKeywords("GPU", List.of("gpu", "그래픽카드", "그래픽 카드", "그래픽", "vga", "rtx", "cuda")),
                new CategoryKeywords("CPU", List.of("cpu", "프로세서", "라이젠", "ryzen", "intel", "인텔")),
                new CategoryKeywords("RAM", List.of("ram", "램", "메모리", "memory"))
        );
        return checks.stream()
                .filter(check -> check.keywords().stream().anyMatch(normalized::contains))
                .map(CategoryKeywords::category)
                .findFirst()
                .orElse(null);
    }

    private List<Map<String, Object>> buildsForBudget(int budgetWon, List<String> appliedPartCategories) {
        return buildCandidatesForBudget(budgetWon, new LinkedHashSet<>(appliedPartCategories)).stream()
                .map(this::buildMap)
                .toList();
    }

    private List<AiBuildCandidate> buildCandidatesForBudget(int budgetWon, Set<String> appliedPartCategories) {
        return TIERS.stream()
                .map(tier -> buildCandidateForTier(tier, budgetWon, appliedPartCategories))
                .toList();
    }

    private AiBuildCandidate buildCandidateForTier(Tier tier, int budgetWon, Set<String> appliedPartCategories) {
        List<PartCandidate> parts = BUILD_CATEGORIES.stream()
                .map(category -> choosePart(category, (int) Math.round(budgetWon * tier.factor() * CATEGORY_RATIOS.get(category))))
                .toList();
        return new AiBuildCandidate(tier, budgetWon, parts, new ArrayList<>(appliedPartCategories));
    }

    private List<Map<String, Object>> applyPartOptions(List<AiBuildCandidate> baseBuilds, String category, List<PartCandidate> options) {
        List<Map<String, Object>> updatedBuilds = new ArrayList<>();
        for (int index = 0; index < baseBuilds.size(); index += 1) {
            AiBuildCandidate baseBuild = baseBuilds.get(index);
            PartCandidate option = options.get(Math.min(index, options.size() - 1));
            List<PartCandidate> nextParts = baseBuild.parts().stream()
                    .map(part -> part.category().equals(category) ? option : part)
                    .toList();
            Set<String> applied = new LinkedHashSet<>(baseBuild.appliedPartCategories());
            applied.add(category);
            updatedBuilds.add(buildMap(new AiBuildCandidate(baseBuild.tier(), baseBuild.budgetWon(), nextParts, new ArrayList<>(applied))));
        }
        return updatedBuilds;
    }

    private List<AiBuildCandidate> currentBuilds(Object value, List<String> warnings) {
        List<Map<String, Object>> rawBuilds = objectMaps(value);
        if (rawBuilds.isEmpty()) {
            return List.of();
        }
        try {
            List<AiBuildCandidate> candidates = new ArrayList<>();
            for (int index = 0; index < rawBuilds.size(); index += 1) {
                Map<String, Object> rawBuild = rawBuilds.get(index);
                Tier tier = tier(text(rawBuild.get("tier")), index);
                List<PartCandidate> parts = objectMaps(rawBuild.get("items")).stream()
                        .map(item -> partByPublicId(text(item.get("partId"))))
                        .toList();
                if (parts.size() < BUILD_CATEGORIES.size()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "currentBuilds의 부품 구성이 부족합니다.");
                }
                int budgetWon = number(rawBuild.get("budgetWon"), totalPrice(parts));
                candidates.add(new AiBuildCandidate(tier, budgetWon, parts, stringList(rawBuild.get("appliedPartCategories"))));
            }
            return candidates;
        } catch (RuntimeException error) {
            warnings.add("이전 AI 추천 조합을 최신 DB 가격으로 복원하지 못해 부품 후보만 표시합니다.");
            return List.of();
        }
    }

    private Map<String, Object> buildMap(AiBuildCandidate build) {
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> items = build.parts().stream()
                .map(part -> partItem(part, "DB 현재가 기준"))
                .toList();
        int totalPrice = build.parts().stream().mapToInt(part -> part.price() == null ? 0 : part.price()).sum();
        List<Map<String, Object>> toolResults = toolResults(build.parts(), build.budgetWon(), warnings);
        warnings.addAll(toolWarnings(toolResults));
        String budgetLabel = formatBudgetLabel(build.budgetWon());
        String id = "ai-" + build.budgetWon() + "-" + build.tier().id() + appliedSuffix(build.appliedPartCategories());
        return MockData.map(
                "id", id,
                "tier", build.tier().id(),
                "label", build.tier().label(),
                "title", budgetLabel + " " + build.tier().title(),
                "summary", budgetLabel + " 예산을 실제 부품 DB와 룰 기반 선택 기준으로 계산한 " + build.tier().title() + " 조합입니다.",
                "totalPrice", totalPrice,
                "badges", badges(budgetLabel, build.tier().title(), build.appliedPartCategories()),
                "budgetWon", build.budgetWon(),
                "budgetLabel", budgetLabel,
                "tierLabel", build.tier().title(),
                "appliedPartCategories", build.appliedPartCategories(),
                "items", items,
                "toolResults", toolResults,
                "warnings", distinct(warnings),
                "confidence", confidence(toolResults, warnings)
        );
    }

    private List<Map<String, Object>> toolResults(List<PartCandidate> parts, int budgetWon, List<String> warnings) {
        try {
            return toolCheckService.checkBuild(parts.stream().map(this::toolPart).toList(), budgetWon);
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

    private List<PartCandidate> partRecommendationOptions(String category) {
        List<PartCandidate> candidates = partCandidates(category);
        List<PartCandidate> picks = new ArrayList<>();
        for (int index : List.of(0, candidates.size() / 2, candidates.size() - 1)) {
            PartCandidate candidate = candidates.get(Math.max(0, Math.min(index, candidates.size() - 1)));
            if (picks.stream().noneMatch(pick -> pick.publicId().equals(candidate.publicId()))) {
                picks.add(candidate);
            }
        }
        for (PartCandidate candidate : candidates) {
            if (picks.size() >= 3) {
                break;
            }
            if (picks.stream().noneMatch(pick -> pick.publicId().equals(candidate.publicId()))) {
                picks.add(candidate);
            }
        }
        if (picks.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, categoryLabel(category) + " 추천 후보가 없습니다.");
        }
        return picks;
    }

    private PartCandidate choosePart(String category, int targetPrice) {
        return partCandidates(category).stream()
                .min(Comparator.comparingInt(part -> Math.abs((part.price() == null ? 0 : part.price()) - targetPrice)))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, categoryLabel(category) + " 추천 가능한 ACTIVE 부품이 없습니다."));
    }

    private List<PartCandidate> partCandidates(String category) {
        List<PartCandidate> toolReady = partCandidates(category, true);
        List<PartCandidate> candidates = toolReady.isEmpty() ? partCandidates(category, false) : toolReady;
        if (candidates.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, categoryLabel(category) + " 추천 가능한 ACTIVE 부품이 없습니다.");
        }
        return candidates;
    }

    private List<PartCandidate> partCandidates(String category, boolean toolReadyOnly) {
        String toolReadyWhere = toolReadyOnly ? " AND coalesce((attributes->>'toolReady')::boolean, false) = true\n" : "";
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
                        """ + toolReadyWhere + """
                        ORDER BY price ASC, id ASC
                        """, category)
                .stream()
                .map(this::partCandidate)
                .toList();
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

    private Map<String, Object> partItem(PartCandidate part, String fallbackNote) {
        return MockData.map(
                "partId", part.publicId(),
                "category", part.category(),
                "name", part.name(),
                "manufacturer", part.manufacturer(),
                "quantity", 1,
                "price", part.price(),
                "note", firstText(text(part.attributes().get("shortSpec")), fallbackNote)
        );
    }

    private ToolBuildPart toolPart(PartCandidate part) {
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

    private static Tier tier(String tierId, int index) {
        if (tierId != null) {
            String normalized = tierId.toLowerCase(Locale.ROOT);
            for (Tier tier : TIERS) {
                if (tier.id().equals(normalized)) {
                    return tier;
                }
            }
        }
        return TIERS.get(Math.max(0, Math.min(index, TIERS.size() - 1)));
    }

    private static List<String> badges(String budgetLabel, String tierTitle, List<String> appliedPartCategories) {
        List<String> badges = new ArrayList<>();
        badges.add(budgetLabel);
        badges.add(tierTitle);
        appliedPartCategories.forEach(category -> badges.add(categoryLabel(category) + " 반영됨"));
        return badges;
    }

    private static String appliedSuffix(List<String> appliedPartCategories) {
        if (appliedPartCategories == null || appliedPartCategories.isEmpty()) {
            return "";
        }
        return "-" + String.join("-", appliedPartCategories).toLowerCase(Locale.ROOT);
    }

    private static String formatBudgetLabel(int budgetWon) {
        return budgetWon % 10_000 == 0 ? (budgetWon / 10_000) + "만원" : String.format("%,d원", budgetWon);
    }

    private static int totalPrice(List<PartCandidate> parts) {
        return parts.stream().mapToInt(part -> part.price() == null ? 0 : part.price()).sum();
    }

    private static String categoryLabel(String category) {
        return CATEGORY_LABELS.getOrDefault(category, category);
    }

    private static List<String> distinct(List<String> values) {
        return values.stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .toList();
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value.toString().isBlank()) {
            return fallback;
        }
        return Integer.parseInt(value.toString());
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
            return (Map<String, Object>) map;
        }
        return Map.of();
    }

    private record Tier(String id, String label, String title, double factor) {
    }

    private record CategoryKeywords(String category, List<String> keywords) {
    }

    private record AiBuildCandidate(Tier tier, int budgetWon, List<PartCandidate> parts, List<String> appliedPartCategories) {
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

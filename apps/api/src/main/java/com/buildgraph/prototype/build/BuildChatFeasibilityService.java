package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 챗봇 요청의 부품 제약(카테고리·스펙·수량·예산)을 실제 부품 DB와 대조하는 타당성 계산기.
 * 역제안의 모든 숫자(최저가·부족액·예산 내 대안)는 여기서 나온다 — LLM은 제약을 구조화만 하고
 * 사실 판단은 이 결정적 질의가 담당해 환각이 낄 자리를 없앤다.
 * BuildChatService가 직접 소유한다(별도 빈 아님) — JdbcTemplate 외 의존이 없다.
 */
public class BuildChatFeasibilityService {
    // 그래픽카드가 사실상 필수인 용도. 이 용도의 최소 구성가에는 GPU가 포함된다.
    private static final Set<String> GPU_REQUIRED_USAGES = Set.of("GAMING", "VIDEO_EDIT", "AI_DEV");
    // AI 학습 용도는 저가 GPU로는 의미가 없어 VRAM 하한을 둔다(내부 자산 기준 보수적 하한).
    private static final int AI_DEV_MIN_VRAM_GB = 8;

    private final JdbcTemplate jdbcTemplate;

    public BuildChatFeasibilityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 이 용도 조합이 그래픽카드를 사실상 필수로 요구하는가(용도 인지 최소 구성가 고지 대상). */
    public static boolean requiresGpu(Collection<String> usageTags) {
        return usageTags != null && usageTags.stream().anyMatch(GPU_REQUIRED_USAGES::contains);
    }

    /**
     * 단일 부품 수치 제약(용량·VRAM·와트·수량·예산) + 닫힌 속성(쿨러 냉각방식·SSD PCIe 세대·케이스 통풍).
     * quantity가 null이면 카테고리 기본 수량(RAM=2, 그 외 1)으로 평가한다.
     * 속성값의 자연어 해석은 LLM이 끝냈고, 여기서는 구조화된 값으로 DB 조회만 한다.
     */
    public record SpecConstraint(
            String category,
            Integer minCapacityGb,
            Integer minVramGb,
            Integer minWattageW,
            Integer quantity,
            Integer maxBudgetWon,
            String coolingType,
            Integer pcieGeneration,
            Boolean airflowFocused
    ) {
        // 기존 6-인자 호출부(용량·VRAM·와트 위주)를 위한 편의 생성자 — 속성은 비운다.
        public SpecConstraint(
                String category,
                Integer minCapacityGb,
                Integer minVramGb,
                Integer minWattageW,
                Integer quantity,
                Integer maxBudgetWon
        ) {
            this(category, minCapacityGb, minVramGb, minWattageW, quantity, maxBudgetWon, null, null, null);
        }

        public static SpecConstraint fromMap(Map<String, Object> source) {
            if (source == null || source.isEmpty()) {
                return null;
            }
            String category = stringValue(source.get("category"));
            if (category == null) {
                return null;
            }
            return new SpecConstraint(
                    category,
                    intValue(source.get("minCapacityGb")),
                    intValue(source.get("minVramGb")),
                    intValue(source.get("minWattageW")),
                    intValue(source.get("quantity")),
                    intValue(source.get("maxBudgetWon")),
                    stringValue(source.get("coolingType")),
                    intValue(source.get("pcieGeneration")),
                    boolValue(source.get("airflowFocused"))
            );
        }

        public boolean hasSpec() {
            return minCapacityGb != null || minVramGb != null || minWattageW != null || hasAttribute();
        }

        /** 닫힌 속성(냉각방식·PCIe 세대·통풍) 중 하나라도 지정됐는가. */
        public boolean hasAttribute() {
            return coolingType != null || pcieGeneration != null || Boolean.TRUE.equals(airflowFocused);
        }

        public int effectiveQuantity() {
            if (quantity != null && quantity >= 1) {
                return quantity;
            }
            // 총용량 제약("32GB 램")은 킷 상품 1개로 충족하는 게 자연스럽다 — RAM 기본 2개(스틱 관례)를
            // 적용하면 "32GB Kit 2개(64GB)"로 과대 계산된다. 용량 명시 시 기본 수량은 1.
            if (minCapacityGb != null) {
                return 1;
            }
            return "RAM".equals(category) ? 2 : 1;
        }

        private static String stringValue(Object value) {
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        }

        private static Integer intValue(Object value) {
            if (value instanceof Number number) {
                int parsed = number.intValue();
                return parsed > 0 ? parsed : null;
            }
            return null;
        }

        private static Boolean boolValue(Object value) {
            return value instanceof Boolean flag ? flag : null;
        }
    }

    public record PartOption(String partId, String name, Integer price, Integer capacityGb, Integer vramGb, Integer wattageW) {
        public int unitPrice() {
            return price == null ? 0 : Math.max(0, price);
        }
    }

    /** 스펙 제약을 전부 만족하는 부품 중 최저가 1개. 없으면 empty(해당 스펙 자체가 자산에 없음). */
    public Optional<PartOption> cheapestMeeting(SpecConstraint constraint) {
        return meetingCheapestFirst(constraint, 1).stream().findFirst();
    }

    /** 스펙 제약을 전부 만족하는 부품을 가격 오름차순 TOP-N으로 — 추천 나열용. */
    public List<PartOption> meetingCheapestFirst(SpecConstraint constraint, int limit) {
        if (constraint == null || constraint.category() == null) {
            return List.of();
        }
        StringBuilder where = new StringBuilder();
        List<Object> params = new ArrayList<>();
        params.add(constraint.category());
        if (constraint.minCapacityGb() != null) {
            where.append(" AND ").append(numericAttribute("capacityGb", "kitCapacityGb", "memoryGb")).append(" >= ?");
            params.add(constraint.minCapacityGb());
        }
        if (constraint.minVramGb() != null) {
            where.append(" AND ").append(numericAttribute("vramGb")).append(" >= ?");
            params.add(constraint.minVramGb());
        }
        if (constraint.minWattageW() != null) {
            where.append(" AND ").append(numericAttribute("wattage", "capacityW")).append(" >= ?");
            params.add(constraint.minWattageW());
        }
        // 닫힌 속성 술어 — 자연어 해석은 LLM이 끝냈고 여기서는 구조화된 값으로 조회만 한다.
        if (constraint.coolingType() != null) {
            // 냉각방식: LIQUID→'LIQUID_AIO'(수랭), AIR→'AIR'(공랭). attributes.coolerType과 대문자 비교.
            where.append(" AND ").append(textAttribute("coolerType")).append(" = ?");
            params.add("LIQUID".equalsIgnoreCase(constraint.coolingType()) ? "LIQUID_AIO" : "AIR");
        }
        if (constraint.pcieGeneration() != null) {
            // generation은 "PCIe 5.0" 같은 문자열 — 첫 정수(세대)만 뽑아 요청 세대 이상인지 비교한다.
            where.append(" AND ").append(pcieGenerationAttribute()).append(" >= ?");
            params.add(constraint.pcieGeneration());
        }
        if (Boolean.TRUE.equals(constraint.airflowFocused())) {
            // 통풍 강조: attributes.airflowFocus = true (boolean)
            where.append(" AND ").append(boolAttribute("airflowFocus"));
        }
        return queryOptions(where.toString(), "price ASC, name ASC", params, limit);
    }

    /**
     * 예산(총액) 안에서 살 수 있는 가장 좋은 스펙의 부품 1개 — "예산 내 대안" 역제안용.
     * 좋음의 기준은 카테고리별 대표 수치(RAM/STORAGE=용량, GPU=VRAM, PSU=와트), 그 외는 벤치 점수 없이 가격 상한 근접.
     */
    public Optional<PartOption> bestUnderBudget(String category, int maxTotalWon, int quantity) {
        return bestUnderBudget(category, maxTotalWon, quantity, 1).stream().findFirst();
    }

    /** 예산 내 최상 스펙 TOP-N — "예산만 준 부품 추천"(10만원짜리 램) 나열용. */
    public List<PartOption> bestUnderBudget(String category, int maxTotalWon, int quantity, int limit) {
        if (category == null || maxTotalWon <= 0) {
            return List.of();
        }
        int unitBudget = maxTotalWon / Math.max(1, quantity);
        String specOrder = switch (category) {
            case "RAM", "STORAGE" -> numericAttribute("capacityGb", "kitCapacityGb", "memoryGb") + " DESC, price ASC";
            case "GPU" -> numericAttribute("vramGb") + " DESC, price ASC";
            case "PSU" -> numericAttribute("wattage", "capacityW") + " DESC, price ASC";
            default -> "price DESC, name ASC";
        };
        List<Object> params = new ArrayList<>();
        params.add(category);
        params.add(unitBudget);
        return queryOptions(" AND price <= ?", specOrder, params, limit);
    }

    /**
     * 용도 인지 최소 구성가: 각 카테고리 최저가 합(RAM은 2개). GPU 필수 용도는 GPU를 포함하며
     * AI_DEV는 VRAM 하한을 적용한다. 자산이 비어 계산 불가면 0을 반환한다(호출측이 고지 생략).
     */
    public int usageMinimumTotal(Collection<String> usageTags) {
        boolean includeGpu = usageTags != null && usageTags.stream().anyMatch(GPU_REQUIRED_USAGES::contains);
        boolean aiDev = usageTags != null && usageTags.contains("AI_DEV");
        int total = 0;
        List<String> categories = new ArrayList<>(List.of("CPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE"));
        if (includeGpu) {
            categories.add(3, "GPU");
        }
        for (String category : categories) {
            SpecConstraint constraint = "GPU".equals(category) && aiDev
                    ? new SpecConstraint("GPU", null, AI_DEV_MIN_VRAM_GB, null, 1, null)
                    : new SpecConstraint(category, null, null, null, null, null);
            Optional<PartOption> cheapest = cheapestMeeting(constraint);
            if (cheapest.isEmpty()) {
                return 0;
            }
            total += cheapest.get().unitPrice() * constraint.effectiveQuantity();
        }
        return total;
    }

    private List<PartOption> queryOptions(String extraWhere, String orderBy, List<Object> params) {
        return queryOptions(extraWhere, orderBy, params, 1);
    }

    private List<PartOption> queryOptions(String extraWhere, String orderBy, List<Object> params, int limit) {
        return jdbcTemplate.queryForList("""
                        SELECT public_id::text AS id,
                               name,
                               price,
                               %s AS capacity_gb,
                               %s AS vram_gb,
                               %s AS wattage_w
                        FROM parts p
                        WHERE category = ?
                          AND status = 'ACTIVE'
                          AND deleted_at IS NULL
                          AND price IS NOT NULL
                        """.formatted(
                                numericAttribute("capacityGb", "kitCapacityGb", "memoryGb"),
                                numericAttribute("vramGb"),
                                numericAttribute("wattage", "capacityW")
                        ) + extraWhere + " ORDER BY " + orderBy + " LIMIT " + Math.max(1, limit),
                        params.toArray())
                .stream()
                .map(row -> new PartOption(
                        DbValueMapper.string(row, "id"),
                        DbValueMapper.string(row, "name"),
                        DbValueMapper.integer(row, "price"),
                        DbValueMapper.integer(row, "capacity_gb"),
                        DbValueMapper.integer(row, "vram_gb"),
                        DbValueMapper.integer(row, "wattage_w")
                ))
                .toList();
    }

    // 문자열 attribute("32GB" 등)에서 숫자만 안전 추출 — BuildChatService.safeNumericAttribute와 동일 패턴.
    private static String numericAttribute(String... keys) {
        StringBuilder coalesce = new StringBuilder("coalesce(");
        for (int i = 0; i < keys.length; i += 1) {
            if (i > 0) {
                coalesce.append(", ");
            }
            coalesce.append("p.attributes->>'").append(keys[i]).append("'");
        }
        coalesce.append(", '0')");
        return "coalesce(NULLIF(regexp_replace(" + coalesce + ", '[^0-9]', '', 'g'), '')::int, 0)";
    }

    // 문자열 attribute를 대문자 정규화해 비교 — 냉각방식('LIQUID_AIO'/'AIR') 등 enum성 매칭용.
    private static String textAttribute(String key) {
        return "upper(coalesce(p.attributes->>'" + key + "', ''))";
    }

    // 불리언 attribute 술어(값이 문자열 'true'/'false'로 저장돼도 안전).
    private static String boolAttribute(String key) {
        return "lower(coalesce(p.attributes->>'" + key + "', 'false')) = 'true'";
    }

    // "PCIe 5.0" 같은 세대 문자열에서 첫 정수(세대)만 추출. numericAttribute는 모든 숫자를 붙여
    // "50"이 되므로(세대 비교 부적합), 여기서는 첫 정수 그룹만 뽑는다.
    private static String pcieGenerationAttribute() {
        return "coalesce(NULLIF(substring(coalesce(p.attributes->>'generation', ''), '[0-9]+'), '')::int, 0)";
    }
}

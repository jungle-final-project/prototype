package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA 확정 버그(B4/B5/B6/표현) 회귀 테스트 — size FAIL 요약의 수치 문구 join,
 * PASS 요약의 검사 범위 한정, 소수 치수의 보수적 반올림, details.issues 구조.
 */
class ToolCheckServiceSizeSummaryTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart gpu(Object lengthMm) {
        return new ToolBuildPart(40L, "gpu-id", "GPU", "테스트 GPU", "NVIDIA", 900_000,
                Map.of("lengthMm", lengthMm));
    }

    private static ToolBuildPart airCooler(int heightMm) {
        return new ToolBuildPart(30L, "cooler-id", "COOLER", "테스트 공랭 쿨러", "딥쿨", 60_000,
                Map.of("coolerType", "AIR", "heightMm", heightMm));
    }

    private static ToolBuildPart psu(int depthMm) {
        return new ToolBuildPart(60L, "psu-id", "PSU", "테스트 파워", "시소닉", 180_000,
                Map.of("capacityW", 850, "depthMm", depthMm));
    }

    private static ToolBuildPart motherboard(Map<String, Object> attributes) {
        return new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 메인보드", "ASUS", 250_000, attributes);
    }

    private static ToolBuildPart pcCase(Map<String, Object> attributes) {
        return new ToolBuildPart(50L, "case-id", "CASE", "테스트 케이스", "리안리", 120_000, attributes);
    }

    private Map<String, Object> size(List<ToolBuildPart> parts) {
        return service.checkBuild(parts, 3_000_000).stream()
                .filter(result -> "size".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("details");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issues(Map<String, Object> tool) {
        return (List<Map<String, Object>>) details(tool).get("issues");
    }

    @Test
    void coolerHeightFailUsesNumericMessage() {
        // 라이브 재현 조합(168mm/165mm) — 무수치 일반문이 아니라 수치 문구가 나와야 한다.
        Map<String, Object> result = size(List.of(
                gpu(300), airCooler(168),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 165))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("쿨러 높이(168mm)가 케이스 허용(165mm)을 초과합니다");
    }

    @Test
    void gpuLengthFailUsesNumericMessage() {
        Map<String, Object> result = size(List.of(
                gpu(360),
                pcCase(Map.of("maxGpuLengthMm", 322))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("GPU 길이(360mm)가 케이스 허용(322mm)을 초과합니다");
    }

    @Test
    void multipleFailConditionsAreAllJoined() {
        // 복수 FAIL이 1건으로 접히지 않고 전부 " · "로 나열돼야 한다(B5).
        Map<String, Object> result = size(List.of(
                gpu(360), airCooler(168), psu(200),
                pcCase(Map.of("maxGpuLengthMm", 322, "maxCpuCoolerHeightMm", 165, "maxPsuLengthMm", 130))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo(
                "GPU 길이(360mm)가 케이스 허용(322mm)을 초과합니다"
                        + " · 쿨러 높이(168mm)가 케이스 허용(165mm)을 초과합니다"
                        + " · 파워 깊이(200mm)가 케이스 허용(130mm)을 초과합니다");
    }

    @Test
    void issuesDetailCarriesPerConditionCategories() {
        // details.issues — 걸린 조건별 {categories, message} 쌍(인사이트 사유별 분리 계약).
        Map<String, Object> result = size(List.of(
                gpu(360), airCooler(168), psu(200),
                pcCase(Map.of("maxGpuLengthMm", 322, "maxCpuCoolerHeightMm", 165, "maxPsuLengthMm", 130))
        ));

        List<Map<String, Object>> issues = issues(result);
        assertThat(issues).hasSize(3);
        assertThat(issues.get(0).get("categories")).isEqualTo(List.of("GPU", "CASE"));
        assertThat(issues.get(0).get("message")).isEqualTo("GPU 길이(360mm)가 케이스 허용(322mm)을 초과합니다");
        assertThat(issues.get(0).get("status")).isEqualTo("FAIL");
        assertThat(issues.get(1).get("categories")).isEqualTo(List.of("COOLER", "CASE"));
        assertThat(issues.get(2).get("categories")).isEqualTo(List.of("PSU", "CASE"));
    }

    @Test
    void warnIssuesCarryTheirOwnCategories() {
        // 결측 WARN 사유도 자기 부품쌍 categories와 함께 내려간다.
        Map<String, Object> result = size(List.of(
                gpu(300), psu(140),
                pcCase(Map.of("maxGpuLengthMm", 400))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        List<Map<String, Object>> issues = issues(result);
        assertThat(issues).hasSize(1);
        assertThat(issues.get(0).get("categories")).isEqualTo(List.of("PSU", "CASE"));
        assertThat(issues.get(0).get("message")).isEqualTo("케이스의 파워 허용 깊이 정보가 없어 장착 검사를 못 했습니다");
        assertThat(issues.get(0).get("status")).isEqualTo("WARN");
    }

    @Test
    void passSummaryListsOnlyCheckedItems() {
        // GPU 길이만 검사한 구성에서 "쿨러 장착, 파워 깊이, 보드 규격"까지 통과했다고 말하면 안 된다.
        Map<String, Object> result = size(List.of(
                gpu(300),
                pcCase(Map.of("maxGpuLengthMm", 400))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(result.get("summary")).isEqualTo("GPU 길이가 케이스 제약 안에 있습니다.");
    }

    @Test
    void passSummaryWithZeroChecksDoesNotOverclaim() {
        // 검사 0건(케이스 없음) — 전부 통과처럼 단정하지 않는다.
        Map<String, Object> result = size(List.of(airCooler(150)));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(result.get("summary")).isEqualTo("케이스 장착 검사를 수행할 부품 조합이 아직 없습니다.");
    }

    @Test
    void fractionalPartDimensionIsCeiled() {
        // 357.6mm를 357로 내림하면 여유가 과대표시된다(B6) — 부품 치수는 올림.
        Map<String, Object> result = size(List.of(
                gpu(357.6),
                pcCase(Map.of("maxGpuLengthMm", 360))
        ));

        assertThat(details(result).get("gpuLengthMm")).isEqualTo(358);
        assertThat(details(result).get("gpuHeadroomMm")).isEqualTo(2);
        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(String.valueOf(result.get("summary"))).contains("여유가 2mm뿐입니다");
    }

    @Test
    void fractionalOverflowFailsInsteadOfTruncatedPass() {
        // 360.2mm > 360mm는 장착 불가다 — 내림 절사로 360<=360 통과가 되면 안 된다.
        Map<String, Object> result = size(List.of(
                gpu(360.2),
                pcCase(Map.of("maxGpuLengthMm", 360))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("GPU 길이(361mm)가 케이스 허용(360mm)을 초과합니다");
    }

    @Test
    void fractionalCaseLimitIsFloored() {
        // 케이스 허용치는 내림 — 165.9mm 허용에 166mm 쿨러는 장착 불가로 본다.
        Map<String, Object> result = size(List.of(
                airCooler(166),
                pcCase(Map.of("maxCpuCoolerHeightMm", 165.9))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("쿨러 높이(166mm)가 케이스 허용(165mm)을 초과합니다");
    }

    @Test
    void unparseableBoardFormFactorQuotesOriginalValue() {
        // 값이 실재하는데 "정보가 없어"라고 말하면 안 된다 — 원문을 인용한다.
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "SSI-EEB")),
                pcCase(Map.of("formFactor", "ATX_MATX_ITX"))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo(
                "지원 여부를 판정할 수 없는 메인보드 규격(SSI-EEB)이라 케이스 장착 검사를 못 했습니다");
    }

    @Test
    void unparseableCaseFormFactorQuotesOriginalValue() {
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "ATX")),
                pcCase(Map.of("formFactor", "DESKTOP_TOWER"))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo(
                "지원 여부를 판정할 수 없는 케이스 규격(DESKTOP_TOWER)이라 장착 검사를 못 했습니다");
    }
}

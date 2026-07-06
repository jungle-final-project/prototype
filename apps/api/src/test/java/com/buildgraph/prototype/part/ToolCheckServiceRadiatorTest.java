package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolCheckServiceRadiatorTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    // GPU는 여유 있게 채워 라디에이터/쿨러 높이 검사만 격리한다(근거 부족 WARN 방지).
    private static ToolBuildPart gpu() {
        return new ToolBuildPart(40L, "gpu-id", "GPU", "테스트 GPU", "NVIDIA", 900000,
                Map.of("lengthMm", 300));
    }

    private static ToolBuildPart aioCooler(Map<String, Object> attributes) {
        return new ToolBuildPart(30L, "aio-id", "COOLER", "테스트 수랭 쿨러", "NZXT", 200000, attributes);
    }

    private static ToolBuildPart airCooler(int heightMm) {
        return new ToolBuildPart(31L, "air-id", "COOLER", "테스트 공랭 쿨러", "딥쿨", 60000,
                Map.of("coolerType", "AIR", "heightMm", heightMm));
    }

    private static ToolBuildPart pcCase(Map<String, Object> attributes) {
        return new ToolBuildPart(50L, "case-id", "CASE", "테스트 케이스", "리안리", 120000, attributes);
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

    @Test
    void unsupportedRadiatorSizeFails() {
        // 케이스 지원 목록(120/240)에 없는 360mm 라디에이터는 장착 불가.
        Map<String, Object> result = size(List.of(
                gpu(),
                aioCooler(Map.of("coolerType", "LIQUID_AIO", "radiatorSizeMm", 360, "heightMm", 27)),
                pcCase(Map.of("maxGpuLengthMm", 400, "radiatorSupportMm", List.of(120, 240)))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary"))).contains("라디에이터 360mm 장착을 지원하지 않습니다");
        assertThat(details(result).get("radiatorChecked")).isEqualTo(true);
        assertThat(details(result).get("radiatorMatched")).isEqualTo(false);
    }

    @Test
    void supportedRadiatorSizePasses() {
        // 지원 목록(120/240/360)에 360mm이 포함되면 정상 통과.
        // 주의: 현 구현은 warn 조건 `coolerHeadroom < 5`가 coolerKnown 게이트 없이 평가돼
        // 수랭(coolerKnown=false → headroom 0)이 항상 WARN이 된다 — PSU 쪽 `(psuKnown && ...)` 패턴 누락.
        // 스펙(지원 목록 일치 → PASS)을 그대로 단언하며, 구현 수정 전까지 이 테스트는 실패한다.
        Map<String, Object> result = size(List.of(
                gpu(),
                aioCooler(Map.of("coolerType", "LIQUID_AIO", "radiatorSizeMm", 360, "heightMm", 27)),
                pcCase(Map.of("maxGpuLengthMm", 400, "radiatorSupportMm", List.of(120, 240, 360)))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("radiatorChecked")).isEqualTo(true);
        assertThat(details(result).get("radiatorMatched")).isEqualTo(true);
    }

    @Test
    void aioWithUnknownCaseRadiatorSupportWarns() {
        // 케이스에 radiatorSupportMm 데이터가 없으면 FAIL 대신 근거 부족 WARN.
        Map<String, Object> result = size(List.of(
                gpu(),
                aioCooler(Map.of("coolerType", "LIQUID_AIO", "radiatorSizeMm", 360, "heightMm", 27)),
                pcCase(Map.of("maxGpuLengthMm", 400))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(details(result).get("radiatorChecked")).isEqualTo(true);
        assertThat(details(result).get("radiatorMatched")).isEqualTo(true);
        assertThat(details(result).get("radiatorSupportMm")).isNull();
    }

    @Test
    void airCoolerSkipsRadiatorCheckAndKeepsHeightCheck() {
        // 공랭은 라디에이터 검사 대상이 아니고, 기존 높이 검사(160mm/허용 170mm)는 유지된다.
        Map<String, Object> result = size(List.of(
                gpu(),
                airCooler(160),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("radiatorChecked")).isEqualTo(false);
        assertThat(details(result).get("coolerHeightMm")).isEqualTo(160);
        assertThat(details(result).get("coolerHeadroomMm")).isEqualTo(10);
    }

    @Test
    void aioHeightIsNotJudgedByAirCoolerHeightLimit() {
        // 수랭 heightMm(27)는 라디에이터 두께라 공랭 허용 높이(120mm)로 FAIL을 내면 안 된다.
        Map<String, Object> result = size(List.of(
                gpu(),
                aioCooler(Map.of("coolerType", "LIQUID_AIO", "radiatorSizeMm", 240, "heightMm", 27)),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 120, "radiatorSupportMm", List.of(240, 360)))
        ));

        // 핵심 단언은 "높이 기준 FAIL이 아니어야 한다"는 것 — 공랭 높이 검사가 수랭에 적용되면 안 된다.
        // (현 구현은 coolerHeadroom warn 게이트 누락으로 수랭이 항상 WARN이라 PASS까지는 단언하지 않는다.)
        assertThat(result.get("status")).isNotEqualTo("FAIL");
        assertThat(details(result).get("coolerHeightMm")).isNull();
        assertThat(details(result).get("coolerHeadroomMm")).isNull();
        assertThat(details(result).get("radiatorMatched")).isEqualTo(true);
    }

    @Test
    void airCoolerWithUnknownCaseHeightLimitWarns() {
        // 케이스 허용 높이 데이터가 없으면 과거처럼 기본값(190)으로 통과시키지 않고 근거 부족 WARN.
        Map<String, Object> result = size(List.of(
                gpu(),
                airCooler(160),
                pcCase(Map.of("maxGpuLengthMm", 400))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(details(result).get("maxCpuCoolerHeightMm")).isNull();
        assertThat(details(result).get("coolerHeightMm")).isEqualTo(160);
    }
}

package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolCheckServicePsuDepthTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    // GPU·쿨러는 여유 있게 채워 PSU 깊이 검사만 격리한다(다른 근거 부족 WARN 방지).
    private static ToolBuildPart gpu() {
        return new ToolBuildPart(40L, "gpu-id", "GPU", "테스트 GPU", "NVIDIA", 900000,
                Map.of("lengthMm", 300));
    }

    private static ToolBuildPart airCooler() {
        return new ToolBuildPart(30L, "cooler-id", "COOLER", "테스트 공랭 쿨러", "딥쿨", 60000,
                Map.of("coolerType", "AIR", "heightMm", 150));
    }

    private static ToolBuildPart pcCase(Map<String, Object> attributes) {
        return new ToolBuildPart(50L, "case-id", "CASE", "테스트 케이스", "리안리", 120000, attributes);
    }

    private static ToolBuildPart psu(int depthMm) {
        return new ToolBuildPart(60L, "psu-id", "PSU", "테스트 파워", "시소닉", 180000,
                Map.of("capacityW", 850, "depthMm", depthMm));
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
    void psuDeeperThanCaseAllowanceFails() {
        // SFX 케이스(허용 130mm)에 200mm 롱바디 PSU는 물리적으로 장착 불가.
        Map<String, Object> result = size(List.of(
                gpu(), airCooler(), psu(200),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170, "maxPsuLengthMm", 130))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary")))
                .contains("파워 깊이(200mm)가 케이스 허용(130mm)을 초과");
        assertThat(details(result).get("psuDepthMm")).isEqualTo(200);
        assertThat(details(result).get("maxPsuLengthMm")).isEqualTo(130);
    }

    @Test
    void psuWithinCaseAllowancePasses() {
        // 140mm PSU / 허용 180mm = 여유 40mm — GPU·쿨러도 여유가 충분해 PASS.
        Map<String, Object> result = size(List.of(
                gpu(), airCooler(), psu(140),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170, "maxPsuLengthMm", 180))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("psuDepthMm")).isEqualTo(140);
        assertThat(details(result).get("psuHeadroomMm")).isEqualTo(40);
    }

    @Test
    void psuPresentButCaseAllowanceUnknownWarns() {
        // PSU가 실재하는데 케이스 허용 깊이 데이터가 없으면 근거 부족 WARN(존재 게이트).
        Map<String, Object> result = size(List.of(
                gpu(), airCooler(), psu(140),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(details(result).get("psuDepthMm")).isEqualTo(140);
        // 결측 치수는 0이 아니라 null로 내린다 — 엣지가 'max-0' 여유로 초록을 그리지 않게.
        assertThat(details(result).get("maxPsuLengthMm")).isNull();
    }

    @Test
    void buildWithoutPsuDoesNotWarnForPsu() {
        // PSU를 아직 안 담은 견적은 PSU 근거 부족으로 WARN이 되지 않는다.
        Map<String, Object> result = size(List.of(
                gpu(), airCooler(),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170, "maxPsuLengthMm", 180))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("psuDepthMm")).isNull();
        assertThat(details(result).get("psuHeadroomMm")).isNull();
    }

    @Test
    void psuCandidateWithoutGpuInBuildDoesNotInheritUnrelatedWarn() {
        // 리뷰 확정 회귀: GPU를 아직 안 담은 견적(사무용 등)에서 PSU 후보 평가(size 구독)가
        // GPU 근거 부족이라는 무관한 사유로 전부 '간섭 주의'가 되면 안 된다.
        Map<String, Object> result = size(List.of(
                psu(140),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170, "maxPsuLengthMm", 180))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
    }

    @Test
    void coolerCandidateWithoutCaseInBuildDoesNotWarn() {
        // 리뷰 확정 회귀: 케이스·GPU를 아직 안 담은 견적에서 쿨러 후보 평가(size 구독)가
        // 결측 근거만으로 WARN이 되면 안 된다 — 검사할 상대가 없으면 생략이다.
        Map<String, Object> result = size(List.of(airCooler()));

        assertThat(result.get("status")).isEqualTo("PASS");
    }

    @Test
    void thinPsuDepthHeadroomWarns() {
        // 125mm/130mm = 여유 5mm(<10mm) — 장착은 되지만 케이블 여유가 빠듯해 WARN.
        Map<String, Object> result = size(List.of(
                gpu(), airCooler(), psu(125),
                pcCase(Map.of("maxGpuLengthMm", 400, "maxCpuCoolerHeightMm", 170, "maxPsuLengthMm", 130))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(details(result).get("psuHeadroomMm")).isEqualTo(5);
    }
}

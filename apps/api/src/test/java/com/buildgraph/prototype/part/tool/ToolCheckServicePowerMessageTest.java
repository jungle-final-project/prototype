package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA 확정 버그(B2) 회귀 테스트 — power 문구는 실제 걸린 조건만 말한다.
 * 벤더 권장 미달이 원인인데 "여유 210W로 빠듯" 같은 모순 문구를 내지 않는다.
 */
class ToolCheckServicePowerMessageTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart gpu(Map<String, Object> attributes) {
        return new ToolBuildPart(40L, "gpu-id", "GPU", "테스트 GPU", "NVIDIA", 900_000, attributes);
    }

    private static ToolBuildPart psu(Map<String, Object> attributes) {
        return new ToolBuildPart(60L, "psu-id", "PSU", "테스트 파워", "시소닉", 180_000, attributes);
    }

    private Map<String, Object> power(List<ToolBuildPart> parts) {
        return service.checkBuild(parts, 3_000_000).stream()
                .filter(result -> "power".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("details");
    }

    @Test
    void vendorShortfallWarnNamesTheRealCause() {
        // 부하 360W/정격 800W는 여유가 넉넉하다 — WARN의 실제 원인은 GPU 권장 850W 미달이고 문구도 그걸 말해야 한다.
        Map<String, Object> result = power(List.of(
                gpu(Map.of("wattage", 300, "requiredSystemPowerW", 850)),
                psu(Map.of("capacityW", 800))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo("GPU 권장 파워 850W에 못 미칩니다(현재 정격 800W)");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("빠듯");
    }

    @Test
    void warnWithBothCausesJoinsBothMessages() {
        // 권장 미달(850>800)과 내부 여유 부족(800<700+120)이 동시에 걸리면 둘 다 말한다.
        Map<String, Object> result = power(List.of(
                gpu(Map.of("wattage", 640, "requiredSystemPowerW", 850)),
                psu(Map.of("capacityW", 800))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo(
                "GPU 권장 파워 850W에 못 미칩니다(현재 정격 800W)"
                        + " · PSU 정격 800W가 예상 부하 700W 대비 여유 100W로 빠듯합니다");
    }

    @Test
    void failWithNegativeHeadroomStatesOverload() {
        // 정격(600W) < 부하(760W): "못 미쳐" 일반문이 아니라 초과·부족량 실값을 말한다(정격−부하=여유 자기일관).
        Map<String, Object> result = power(List.of(
                gpu(Map.of("wattage", 700)),
                psu(Map.of("capacityW", 600))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("예상 부하 760W가 PSU 정격 600W를 초과합니다(부족 160W)");
        assertThat(details(result).get("ratedHeadroomW")).isEqualTo(-160);
    }

    @Test
    void failWithPositiveHeadroomDoesNotClaimOverload() {
        // 여유 +60W FAIL(최소 여유 미달) — "부하에 못 미쳐"라는 거짓 진술 대신 실제 여유를 말한다.
        Map<String, Object> result = power(List.of(
                gpu(Map.of("wattage", 440)),
                psu(Map.of("capacityW", 560))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("PSU 정격 560W가 예상 부하 500W 대비 여유 60W뿐이라 부족합니다");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("권장");
    }

    @Test
    void missingPsuCapacityWarnsInsteadOfZeroWattFail() {
        // capacityW 결측을 0W로 계산해 '정격 0W' FAIL 확정문을 내면 안 된다 — 검사 생략 + 정보 없음 WARN.
        Map<String, Object> result = power(List.of(
                gpu(Map.of("wattage", 300)),
                psu(Map.of("depthMm", 140))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo("파워 용량 정보가 없어 전력 검사를 못 했습니다");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("0W");
        // 결측은 size 관례대로 null — 0을 실으면 엣지가 '현재 파워 0W' 허구 숫자를 그린다.
        assertThat(details(result).get("psuRatedCapacityW")).isNull();
        assertThat(details(result).get("ratedHeadroomW")).isNull();
        assertThat(details(result).get("ratedLoadPercent")).isNull();
    }

    @Test
    void missingPsuFailsWithoutFabricatedNumbers() {
        // PSU 미장착(레거시 직접 호출 경로) — '정격 0W' 같은 허구 숫자 없이 상태만 말한다.
        Map<String, Object> result = power(List.of(gpu(Map.of("wattage", 300))));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("파워(PSU)가 없어 시스템 전력을 공급할 수 없습니다");
        assertThat(details(result).get("psuRatedCapacityW")).isNull();
        assertThat(details(result).get("ratedLoadPercent")).isNull();
    }

    @Test
    void unknownGpuWattageLowersConfidenceAndAnnotates() {
        // GPU wattage 결측은 부하 0으로 조용히 계상된다 — 판정은 유지하되 신뢰도를 낮추고 문구에 명시한다.
        Map<String, Object> result = power(List.of(
                gpu(Map.of("lengthMm", 300)),
                psu(Map.of("capacityW", 850))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(result.get("confidence")).isEqualTo("LOW");
        assertThat(String.valueOf(result.get("summary")))
                .contains("일부 부품의 소비전력 정보가 없어 실제 부하는 더 높을 수 있습니다");
        assertThat(details(result).get("wattageUnknownCategories")).isEqualTo(List.of("GPU"));
    }

    @Test
    void knownWattagesDoNotCarryUncertaintyNote() {
        Map<String, Object> result = power(List.of(
                gpu(Map.of("wattage", 300)),
                psu(Map.of("capacityW", 850))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("소비전력 정보가 없어");
        assertThat(details(result).get("wattageUnknownCategories")).isNull();
    }
}

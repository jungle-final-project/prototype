package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolCheckServiceCoolerTdpTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart cpu(int tdpW) {
        return new ToolBuildPart(1L, "cpu-id", "CPU", "테스트 CPU", "AMD", 450000,
                Map.of("socket", "AM5", "tdpW", tdpW));
    }

    private static ToolBuildPart cpuWithoutTdpData() {
        return new ToolBuildPart(1L, "cpu-id", "CPU", "TDP 미확인 CPU", "AMD", 450000,
                Map.of("socket", "AM5"));
    }

    private static ToolBuildPart cooler(int tdpW) {
        // 소켓은 CPU와 일치시켜 소켓 검사가 아닌 TDP 검사만 격리한다.
        return new ToolBuildPart(30L, "cooler-id", "COOLER", "테스트 쿨러", "딥쿨", 60000,
                Map.of("socketSupport", List.of("AM5"), "tdpW", tdpW));
    }

    private Map<String, Object> compatibility(List<ToolBuildPart> parts) {
        return service.checkBuild(parts, 3_000_000).stream()
                .filter(result -> "compatibility".equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("details");
    }

    @Test
    void underpoweredCoolerFailsEvenWithMatchingSocket() {
        // 소켓이 맞아도 65W급 쿨러에 170W CPU면 조립은 돼도 냉각이 안 된다.
        Map<String, Object> result = compatibility(List.of(cpu(170), cooler(65)));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary")))
                .contains("쿨러 TDP 대응(65W)이 CPU TDP(170W)에 못 미쳐");
        assertThat(details(result).get("coolerTdpChecked")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpMatched")).isEqualTo(false);
        assertThat(details(result).get("cpuTdpW")).isEqualTo(170);
        assertThat(details(result).get("coolerTdpW")).isEqualTo(65);
    }

    @Test
    void coolerWithAmpleTdpHeadroomPasses() {
        // 265W 쿨러는 170W CPU 대비 여유 95W(20% 이상)라 정상 통과.
        Map<String, Object> result = compatibility(List.of(cpu(170), cooler(265)));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("coolerTdpChecked")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpMatched")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpHeadroomW")).isEqualTo(95);
    }

    @Test
    void thinTdpMarginKeepsPassStatusButFlagsMargin() {
        // 140W 쿨러 ≥ 130W CPU라 장착 가능. 마진 20% 미만(156W 미달) 경고는 details/summary로만 내린다 —
        // 툴 status를 WARN으로 올리면 compatibility를 구독하는 RAM/보드 후보 전체가 무관한 사유로 '간섭 주의'가 된다.
        Map<String, Object> result = compatibility(List.of(cpu(130), cooler(140)));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(String.valueOf(result.get("summary"))).contains("쿨러 TDP 여유가 20% 미만");
        assertThat(details(result).get("coolerTdpChecked")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpMatched")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpMarginLow")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpHeadroomW")).isEqualTo(10);
    }

    @Test
    void missingCpuTdpDataSkipsCoolerTdpCheck() {
        // CPU tdpW가 없으면(신규 인테이크 유입 등) 검사를 생략한다 — 없는 데이터로 FAIL을 내지 않는다.
        Map<String, Object> result = compatibility(List.of(cpuWithoutTdpData(), cooler(65)));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("coolerTdpChecked")).isEqualTo(false);
        assertThat(details(result).get("coolerTdpMatched")).isEqualTo(true);
        assertThat(details(result).get("coolerTdpHeadroomW")).isNull();
    }

    @Test
    void buildWithoutCoolerSkipsCoolerTdpCheck() {
        // 쿨러를 아직 안 담은 견적은 TDP 검사 대상이 아니다.
        Map<String, Object> result = compatibility(List.of(cpu(170)));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("coolerTdpChecked")).isEqualTo(false);
        assertThat(details(result).get("coolerTdpMatched")).isEqualTo(true);
    }
}

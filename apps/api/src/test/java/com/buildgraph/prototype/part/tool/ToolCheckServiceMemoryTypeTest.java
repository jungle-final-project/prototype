package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * QA 강한 의심(P1급) 회귀 테스트 — memoryType 결측 'DDR5' 날조 제거(결측=검사 생략+WARN),
 * 마지막 RAM 행만 비교하던 검사의 전 행 순회 전환, 쿨러 소켓(물리 불가) 우선순위.
 */
class ToolCheckServiceMemoryTypeTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart board(Map<String, Object> attributes) {
        return new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 메인보드", "ASUS", 250_000, attributes);
    }

    private static ToolBuildPart ram(String id, Map<String, Object> attributes) {
        return new ToolBuildPart(21L, id, "RAM", "테스트 램 " + id, "삼성", 150_000, attributes);
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> issues(Map<String, Object> tool) {
        return (List<Map<String, Object>>) details(tool).get("issues");
    }

    @Test
    void missingRamMemoryTypeWarnsInsteadOfGuessing() {
        // RAM 규격 결측 — 'DDR5'로 치환해 비교하지 않고 검사 생략을 명시한다.
        Map<String, Object> result = compatibility(List.of(
                board(Map.of("socket", "AM5", "memoryType", "DDR5")),
                ram("no-type", Map.of("moduleCount", 2))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo("메모리 규격 정보가 없어 검사를 못 했습니다");
        assertThat(details(result).get("memoryTypeChecked")).isEqualTo(false);
        assertThat(details(result).get("memoryTypeMatched")).isEqualTo(true);
    }

    @Test
    void missingBoardMemoryTypeWarnsInsteadOfSilentPass() {
        // 보드 규격 결측 — 예전에는 DDR5==DDR5 날조 비교로 조용히 PASS였다.
        Map<String, Object> result = compatibility(List.of(
                board(Map.of("socket", "AM5")),
                ram("ddr5", Map.of("memoryType", "DDR5"))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(result.get("summary")).isEqualTo("메모리 규격 정보가 없어 검사를 못 했습니다");
    }

    @Test
    void missingTypeNeverFabricatesDdr5Fail() {
        // DDR4 보드 + 규격 결측 RAM — 예전에는 "RAM 규격(DDR5)" 날조 FAIL이 나갔다.
        Map<String, Object> result = compatibility(List.of(
                board(Map.of("socket", "AM5", "memoryType", "DDR4")),
                ram("no-type", Map.of())
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("DDR5");
    }

    @Test
    void ramWithoutBoardDoesNotWarnAboutMissingType() {
        // 결측 WARN은 RAM·보드가 둘 다 담겼을 때만 낸다(size의 존재 게이트 관례).
        Map<String, Object> result = compatibility(List.of(ram("no-type", Map.of())));

        assertThat(result.get("status")).isEqualTo("PASS");
    }

    @Test
    void mismatchedMemoryTypeFailsWithRealValues() {
        Map<String, Object> result = compatibility(List.of(
                board(Map.of("socket", "AM5", "memoryType", "DDR5")),
                ram("ddr4", Map.of("memoryType", "DDR4"))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("RAM 규격(DDR4)과 메인보드 지원 메모리(DDR5)가 다릅니다");
        assertThat(details(result).get("memoryTypeChecked")).isEqualTo(true);
        assertThat(details(result).get("memoryTypeMatched")).isEqualTo(false);
    }

    @Test
    void anyRamRowMismatchFailsRegardlessOfRowOrder() {
        // 마지막 행(DDR5)만 비교하면 앞 행(DDR4) 불일치가 조용히 PASS가 된다 — 전 행 순회 회귀.
        Map<String, Object> result = compatibility(List.of(
                board(Map.of("socket", "AM5", "memoryType", "DDR5")),
                ram("ddr4", Map.of("memoryType", "DDR4")),
                ram("ddr5", Map.of("memoryType", "DDR5"))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary")))
                .isEqualTo("RAM 규격(DDR4)과 메인보드 지원 메모리(DDR5)가 다릅니다");
    }

    @Test
    void mixedRamTypesFailEvenWithoutBoard() {
        // DDR4+DDR5 혼합은 어떤 보드에도 함께 장착할 수 없다.
        Map<String, Object> result = compatibility(List.of(
                ram("ddr4", Map.of("memoryType", "DDR4")),
                ram("ddr5", Map.of("memoryType", "DDR5"))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo(
                "서로 다른 RAM 규격(DDR4, DDR5)이 함께 담겨 있어 같은 보드에 장착할 수 없습니다");
        assertThat(details(result).get("ramMemoryTypes")).isEqualTo(List.of("DDR4", "DDR5"));
    }

    @Test
    void coolerSocketMismatchOutranksCoolerTdpInSummary() {
        // 물리 장착 불가(소켓)가 냉각 부족(TDP)에 가려지면 안 된다(B3) — 그래프 엣지와 우선순위 통일.
        Map<String, Object> result = compatibility(List.of(
                new ToolBuildPart(1L, "cpu-id", "CPU", "테스트 CPU", "인텔", 450_000,
                        Map.of("socket", "LGA1851", "tdpW", 125)),
                new ToolBuildPart(30L, "cooler-id", "COOLER", "테스트 쿨러", "딥쿨", 60_000,
                        Map.of("socketSupport", List.of("AM5"), "tdpW", 65))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("쿨러가 CPU 소켓(LGA1851)을 지원하지 않습니다");
        // 걸린 조건은 둘 다 details.issues로 내려간다 — 소켓이 먼저, TDP가 다음.
        List<Map<String, Object>> issues = issues(result);
        assertThat(issues).hasSize(2);
        assertThat(issues.get(0).get("message")).isEqualTo("쿨러가 CPU 소켓(LGA1851)을 지원하지 않습니다");
        assertThat(issues.get(0).get("categories")).isEqualTo(List.of("COOLER", "CPU"));
        assertThat(issues.get(1).get("message")).isEqualTo("쿨러 TDP 65W가 CPU TDP 125W에 못 미쳐 냉각이 부족합니다");
    }

    @Test
    void registeredRamSummaryUsesUserLanguage() {
        // 'REGISTERED' 합성 토큰 원어를 그대로 노출하지 않는다 — details 원문은 하위호환 유지.
        Map<String, Object> result = compatibility(List.of(
                ram("rdimm", Map.of("memoryType", "DDR5", "registered", true))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("레지스터드(서버용) RAM이라 데스크탑 보드에 장착할 수 없습니다");
        assertThat(details(result).get("ramBadFormFactors")).isEqualTo(List.of("REGISTERED"));
    }
}

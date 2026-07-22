package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 속성 파싱 방어 회귀 테스트 — 비숫자 문자열로 검사 전체가 500으로 죽지 않아야 하고(P2),
 * 공백/빈 소켓 문자열로 가짜 FAIL·빈 괄호가 나오지 않아야 하며, AIO 표기 변형을 수랭으로 인식해야 한다.
 */
class ToolCheckServiceAttributeParsingTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private Map<String, Object> toolResult(List<ToolBuildPart> parts, String tool) {
        return service.checkBuild(parts, 3_000_000).stream()
                .filter(result -> tool.equals(result.get("tool")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Map<String, Object> tool) {
        return (Map<String, Object>) tool.get("details");
    }

    @Test
    void nonNumericModuleCountDoesNotThrow() {
        // "2개" 같은 문자열 속성이 인입돼도 NumberFormatException으로 그래프 전체가 죽으면 안 된다 —
        // 파싱 실패는 결측과 동일하게 취급한다(moduleCount 기본 1).
        Map<String, Object> result = toolResult(List.of(
                new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 보드", "ASUS", 250_000,
                        Map.of("socket", "AM5", "memoryType", "DDR5", "memorySlots", 2)),
                new ToolBuildPart(21L, "ram-id", "RAM", "테스트 램", "삼성", 150_000,
                        Map.of("memoryType", "DDR5", "moduleCount", "2개"), 2)
        ), "compatibility");

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("ramSticksTotal")).isEqualTo(2);
    }

    @Test
    void paddedSocketStringsStillMatch() {
        // " AM5" vs "AM5" 공백 차이로 가짜 소켓 FAIL이 나면 안 된다.
        Map<String, Object> result = toolResult(List.of(
                new ToolBuildPart(1L, "cpu-id", "CPU", "테스트 CPU", "AMD", 450_000, Map.of("socket", " AM5 ")),
                new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 보드", "ASUS", 250_000,
                        Map.of("socket", "AM5", "memoryType", "DDR5"))
        ), "compatibility");

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("socketMatched")).isEqualTo(true);
    }

    @Test
    void blankSocketIsTreatedAsMissing() {
        // 빈 문자열 소켓은 비교 대상이 아니다 — "CPU 소켓()과 …" 빈 괄호 FAIL 금지.
        // 다만 조용한 PASS로 둔갑해서도 안 된다: 결측은 '검사를 못 했다'로 명시한다(메모리 규격 관례).
        Map<String, Object> result = toolResult(List.of(
                new ToolBuildPart(1L, "cpu-id", "CPU", "테스트 CPU", "AMD", 450_000, Map.of("socket", "  ")),
                new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 보드", "ASUS", 250_000,
                        Map.of("socket", "AM5", "memoryType", "DDR5"))
        ), "compatibility");

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(String.valueOf(result.get("summary"))).doesNotContain("()");
        assertThat(String.valueOf(result.get("summary"))).contains("소켓 정보가 없어 검사를 못 했습니다");
        assertThat(details(result).get("socketMatched")).isNull();
        assertThat(details(result).get("socketChecked")).isEqualTo(false);
    }

    @Test
    void missingCoolerSocketSupportIsReportedAsUncheckedNotCompatible() {
        // socketSupport 목록이 없는 쿨러는 '쿨러 호환' 보증 대상이 아니다 — 미검증을 명시한다.
        Map<String, Object> result = toolResult(List.of(
                new ToolBuildPart(1L, "cpu-id", "CPU", "테스트 CPU", "AMD", 450_000, Map.of("socket", "AM5")),
                new ToolBuildPart(30L, "cooler-id", "COOLER", "테스트 쿨러", "딥쿨", 80_000, Map.of("heightMm", 155))
        ), "compatibility");

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(String.valueOf(result.get("summary"))).contains("쿨러의 소켓 지원 정보가 없어 검사를 못 했습니다");
        assertThat(details(result).get("coolerSocketMatched")).isNull();
        assertThat(details(result).get("coolerSocketChecked")).isEqualTo(false);
    }

    @Test
    void aioAliasIsRecognizedAsLiquidCooler() {
        // 'AIO' 표기 쿨러가 공랭으로 오인되면 라디에이터 검사가 통째로 생략된다(거짓 초록).
        Map<String, Object> result = toolResult(List.of(
                new ToolBuildPart(30L, "aio-id", "COOLER", "테스트 AIO 쿨러", "NZXT", 200_000,
                        Map.of("coolerType", "AIO", "radiatorSizeMm", 360, "heightMm", 27)),
                new ToolBuildPart(50L, "case-id", "CASE", "테스트 케이스", "리안리", 120_000,
                        Map.of("maxCpuCoolerHeightMm", 170, "radiatorSupportMm", List.of(120, 240)))
        ), "size");

        assertThat(details(result).get("radiatorChecked")).isEqualTo(true);
        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(result.get("summary")).isEqualTo("케이스가 라디에이터 360mm 장착을 지원하지 않습니다");
    }

    @Test
    void koreanLiquidLabelIsRecognizedAsLiquidCooler() {
        Map<String, Object> result = toolResult(List.of(
                new ToolBuildPart(30L, "aio-id", "COOLER", "테스트 수랭 쿨러", "NZXT", 200_000,
                        Map.of("coolerType", "수랭 일체형", "radiatorSizeMm", 240, "heightMm", 27)),
                new ToolBuildPart(50L, "case-id", "CASE", "테스트 케이스", "리안리", 120_000,
                        Map.of("maxCpuCoolerHeightMm", 170, "radiatorSupportMm", List.of(240, 360)))
        ), "size");

        assertThat(details(result).get("radiatorChecked")).isEqualTo(true);
        assertThat(details(result).get("radiatorMatched")).isEqualTo(true);
        assertThat(result.get("status")).isEqualTo("PASS");
    }
}

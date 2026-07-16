package com.buildgraph.prototype.part.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 감사 P1-1(b): 메인보드 폼팩터 vs 케이스 지원 규격 검사 — ITX 케이스 + ATX 보드 false PASS 제거. */
class ToolCheckServiceBoardFormFactorTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart motherboard(Map<String, Object> attributes) {
        return new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 메인보드", "ASUS", 250000, attributes);
    }

    private static ToolBuildPart pcCase(Map<String, Object> attributes) {
        return new ToolBuildPart(50L, "case-id", "CASE", "테스트 케이스", "프랙탈", 120000, attributes);
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
    void atxBoardInItxOnlyCaseFails() {
        // ITX 전용 케이스(Fractal Terra 류)에 ATX 보드 — 나사 구멍부터 안 맞는 조합.
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "ATX")),
                pcCase(Map.of("formFactor", "MINI_ITX"))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary")))
                .contains("케이스가 ATX 규격 메인보드 장착을 지원하지 않습니다");
        assertThat(details(result).get("boardFormFactor")).isEqualTo("ATX");
        assertThat(details(result).get("caseMaxFormFactor")).isEqualTo("Mini-ITX");
        assertThat(details(result).get("boardFormFactorMatched")).isEqualTo(false);
    }

    @Test
    void smallerBoardInBiggerCasePasses() {
        // 표준 홀 규격 위계: M-ATX 보드는 MATX_ITX 케이스에 장착 가능.
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "M-ATX")),
                pcCase(Map.of("formFactor", "MATX_ITX"))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("boardFormFactorChecked")).isEqualTo(true);
        assertThat(details(result).get("boardFormFactorMatched")).isEqualTo(true);
    }

    @Test
    void legacyVocabularyIsAbsorbed() {
        // 백필 전 표기 변형('MINI_ITX'/'Micro-ATX'/'mATX_ATX')도 같은 랭크로 이해한다.
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "Micro-ATX")),
                pcCase(Map.of("formFactor", "mATX_ATX"))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("boardFormFactor")).isEqualTo("M-ATX");
        assertThat(details(result).get("caseMaxFormFactor")).isEqualTo("ATX");
    }

    @Test
    void serverCaseSupportsEatxBoard() {
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "E-ATX")),
                pcCase(Map.of("formFactor", "SSI_EEB_EATX_ATX_MATX_ITX"))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("caseMaxFormFactor")).isEqualTo("E-ATX");
    }

    @Test
    void boardWithoutFormFactorDataWarns() {
        // 보드·케이스가 실재하는데 폼팩터 근거가 없으면 생략이 아니라 근거 부족 WARN(존재 게이트).
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("memorySlots", 4)),
                pcCase(Map.of("formFactor", "ATX_MATX_ITX"))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(details(result).get("boardFormFactor")).isNull();
        assertThat(details(result).get("boardFormFactorChecked")).isEqualTo(false);
    }

    @Test
    void unknownCaseSupportStringWarnsInsteadOfGuessing() {
        // 해석 불가한 케이스 지원 문자열은 임의 판정하지 않고 근거 부족 WARN.
        Map<String, Object> result = size(List.of(
                motherboard(Map.of("formFactor", "ATX")),
                pcCase(Map.of("formFactor", "DESKTOP_TOWER"))
        ));

        assertThat(result.get("status")).isEqualTo("WARN");
        assertThat(details(result).get("caseMaxFormFactor")).isNull();
    }

    @Test
    void boardWithoutCaseDoesNotWarn() {
        // 케이스를 아직 안 담은 견적은 폼팩터 근거 부족으로 WARN이 되지 않는다 — 상대가 없으면 생략.
        Map<String, Object> result = size(List.of(motherboard(Map.of("formFactor", "ATX"))));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("boardFormFactorChecked")).isEqualTo(false);
    }
}

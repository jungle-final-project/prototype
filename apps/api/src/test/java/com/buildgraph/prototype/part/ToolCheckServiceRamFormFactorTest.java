package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolCheckServiceRamFormFactorTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart ram(String id, Map<String, Object> attributes) {
        return new ToolBuildPart(20L, id, "RAM", "테스트 램 " + id, "삼성", 150000, attributes);
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
    void sodimmFailsOnDesktopBoard() {
        // 노트북용 SODIMM은 데스크탑 보드에 물리적으로 장착이 안 된다.
        Map<String, Object> result = compatibility(List.of(
                ram("sodimm", Map.of("memoryType", "DDR5", "formFactor", "SODIMM"))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary"))).contains("장착할 수 없는 RAM 폼팩터(SODIMM)");
        assertThat(details(result).get("ramFormFactorChecked")).isEqualTo(true);
        assertThat(details(result).get("ramFormFactorMatched")).isEqualTo(false);
        assertThat(details(result).get("ramBadFormFactors")).isEqualTo(List.of("SODIMM"));
    }

    @Test
    void registeredRamWithoutFormFactorFailsAsRegistered() {
        // 서버용 RDIMM은 formFactor 표기가 없어도 registered=true만으로 걸러낸다.
        Map<String, Object> result = compatibility(List.of(
                ram("rdimm", Map.of("memoryType", "DDR5", "registered", true))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(details(result).get("ramFormFactorChecked")).isEqualTo(true);
        assertThat(details(result).get("ramFormFactorMatched")).isEqualTo(false);
        assertThat(details(result).get("ramBadFormFactors")).isEqualTo(List.of("REGISTERED"));
    }

    @Test
    void udimmUnbufferedRamPasses() {
        // 일반 데스크탑용 UDIMM(unbuffered)은 정상 통과.
        Map<String, Object> result = compatibility(List.of(
                ram("udimm", Map.of("memoryType", "DDR5", "formFactor", "UDIMM", "registered", false))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("ramFormFactorChecked")).isEqualTo(true);
        assertThat(details(result).get("ramFormFactorMatched")).isEqualTo(true);
        assertThat(details(result).get("ramBadFormFactors")).isNull();
    }

    @Test
    void ramWithoutFormFactorDataSkipsCheck() {
        // formFactor/registered 속성이 전부 없으면 검사를 생략한다 — 없는 데이터로 FAIL을 내지 않는다.
        Map<String, Object> result = compatibility(List.of(
                ram("unknown", Map.of("memoryType", "DDR5"))
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("ramFormFactorChecked")).isEqualTo(false);
        assertThat(details(result).get("ramFormFactorMatched")).isEqualTo(true);
    }

    @Test
    void singleBadRowAmongMultipleRamRowsFails() {
        // byCategory는 카테고리당 1개로 접히므로, RAM 2행 중 뒤쪽 행의 SODIMM도 놓치지 않아야 한다.
        Map<String, Object> result = compatibility(List.of(
                ram("good", Map.of("memoryType", "DDR5", "formFactor", "UDIMM")),
                ram("bad", Map.of("memoryType", "DDR5", "formFactor", "SODIMM"))
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary"))).contains("장착할 수 없는 RAM 폼팩터(SODIMM)");
        assertThat(details(result).get("ramFormFactorMatched")).isEqualTo(false);
        assertThat(details(result).get("ramBadFormFactors")).isEqualTo(List.of("SODIMM"));
    }
}

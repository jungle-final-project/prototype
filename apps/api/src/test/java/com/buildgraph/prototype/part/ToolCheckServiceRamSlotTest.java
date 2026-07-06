package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class ToolCheckServiceRamSlotTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart board(int memorySlots) {
        return new ToolBuildPart(10L, "board-id", "MOTHERBOARD", "테스트 보드", "ASUS", 250000,
                Map.of("socket", "AM5", "memoryType", "DDR5", "memorySlots", memorySlots));
    }

    private static ToolBuildPart boardWithoutSlotData() {
        return new ToolBuildPart(10L, "board-id", "MOTHERBOARD", "슬롯 미확인 보드", "ASUS", 250000,
                Map.of("socket", "AM5", "memoryType", "DDR5"));
    }

    private static ToolBuildPart ram(String id, Map<String, Object> attributes, Integer quantity) {
        return new ToolBuildPart(20L, id, "RAM", "테스트 램 " + id, "삼성", 150000, attributes, quantity);
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
    void twoStickKitTimesTwoFitsFourSlotBoard() {
        Map<String, Object> result = compatibility(List.of(
                board(4),
                ram("kit", Map.of("memoryType", "DDR5", "moduleCount", 2), 2)
        ));

        assertThat(details(result).get("ramSticksTotal")).isEqualTo(4);
        assertThat(details(result).get("memorySlots")).isEqualTo(4);
        assertThat(details(result).get("ramSlotsChecked")).isEqualTo(true);
        assertThat(details(result).get("ramSlotsMatched")).isEqualTo(true);
    }

    @Test
    void kitQuantityOverflowingSlotsFailsCompatibility() {
        // 2개들이 킷 × 수량 3 = 스틱 6개 > 4슬롯: 물리적으로 장착 불가.
        Map<String, Object> result = compatibility(List.of(
                board(4),
                ram("kit", Map.of("memoryType", "DDR5", "moduleCount", 2), 3)
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary"))).contains("메모리 슬롯").contains("6개");
        assertThat(details(result).get("ramSticksTotal")).isEqualTo(6);
        assertThat(details(result).get("ramSlotsMatched")).isEqualTo(false);
    }

    @Test
    void mixedRamItemsAreSummedAcrossProducts() {
        // 단품(모듈 수 미기재=1) 수량 2 + 2개들이 킷 1개 = 스틱 4개 > 2슬롯(ITX).
        Map<String, Object> result = compatibility(List.of(
                board(2),
                ram("single", Map.of("memoryType", "DDR5"), 2),
                ram("kit", Map.of("memoryType", "DDR5", "moduleCount", 2), 1)
        ));

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(details(result).get("ramSticksTotal")).isEqualTo(4);
        assertThat(details(result).get("memorySlots")).isEqualTo(2);
        assertThat(details(result).get("ramSlotsMatched")).isEqualTo(false);
    }

    @Test
    void boardWithoutMemorySlotsDataSkipsSlotCheck() {
        // 제조사 인테이크로 유입된 보드는 memorySlots가 없을 수 있다 — 없는 데이터로 FAIL을 내지 않는다.
        Map<String, Object> result = compatibility(List.of(
                boardWithoutSlotData(),
                ram("kit", Map.of("memoryType", "DDR5", "moduleCount", 2), 3)
        ));

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("ramSlotsChecked")).isEqualTo(false);
        assertThat(details(result).get("ramSlotsMatched")).isEqualTo(true);
    }
}

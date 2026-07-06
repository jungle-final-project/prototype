package com.buildgraph.prototype.part;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 감사 P1-2: M.2 SSD 장착 수 vs 보드 M.2 슬롯 검사 — SSD가 유일한 무검사 부품이던 것을 해소. */
class ToolCheckServiceStorageSlotTest {
    private final ToolCheckService service = new ToolCheckService(mock(JdbcTemplate.class));

    private static ToolBuildPart motherboard(int m2Slots) {
        return new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 메인보드", "ASUS", 250000,
                Map.of("socket", "AM5", "memoryType", "DDR5", "m2Slots", m2Slots));
    }

    private static ToolBuildPart boardWithoutM2Data() {
        return new ToolBuildPart(20L, "board-id", "MOTHERBOARD", "테스트 메인보드", "ASUS", 250000,
                Map.of("socket", "AM5", "memoryType", "DDR5"));
    }

    private static ToolBuildPart m2Ssd(String id, int quantity) {
        return new ToolBuildPart(70L, id, "STORAGE", "M.2 NVMe SSD " + id, "삼성", 150000,
                Map.of("interface", "M.2 NVMe", "formFactor", "M.2 2280", "capacityGb", 1000), quantity);
    }

    private static ToolBuildPart sataSsd(String id, int quantity) {
        return new ToolBuildPart(71L, id, "STORAGE", "SATA SSD " + id, "삼성", 90000,
                Map.of("interface", "SATA", "formFactor", "2.5 inch", "capacityGb", 1000), quantity);
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
    void threeM2SsdsInTwoSlotBoardFails() {
        // 2 M.2 슬롯 보드에 M.2 SSD 3종 — 물리적으로 다 못 꽂는다.
        List<ToolBuildPart> parts = new ArrayList<>(List.of(
                motherboard(2), m2Ssd("ssd-1", 1), m2Ssd("ssd-2", 1), m2Ssd("ssd-3", 1)
        ));
        Map<String, Object> result = compatibility(parts);

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(String.valueOf(result.get("summary")))
                .contains("M.2 SSD 수(3개)가 메인보드 M.2 슬롯(2개)을 초과");
        assertThat(details(result).get("m2StorageTotal")).isEqualTo(3);
        assertThat(details(result).get("m2Slots")).isEqualTo(2);
        assertThat(details(result).get("m2SlotsMatched")).isEqualTo(false);
    }

    @Test
    void quantityCountsAgainstSlots() {
        // 같은 M.2 SSD 3개(수량 가중) — 스틱 수처럼 수량을 M.2 슬롯에 계상한다.
        List<ToolBuildPart> parts = new ArrayList<>(List.of(motherboard(2), m2Ssd("ssd-1", 3)));
        Map<String, Object> result = compatibility(parts);

        assertThat(result.get("status")).isEqualTo("FAIL");
        assertThat(details(result).get("m2StorageTotal")).isEqualTo(3);
    }

    @Test
    void sataSsdsDoNotConsumeM2Slots() {
        // SATA 2.5인치 SSD는 M.2 슬롯을 쓰지 않는다 — M.2 1개 + SATA 3개는 2슬롯 보드에 PASS.
        List<ToolBuildPart> parts = new ArrayList<>(List.of(
                motherboard(2), m2Ssd("ssd-1", 1), sataSsd("sata-1", 3)
        ));
        Map<String, Object> result = compatibility(parts);

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("m2StorageTotal")).isEqualTo(1);
        assertThat(details(result).get("m2SlotsMatched")).isEqualTo(true);
    }

    @Test
    void twoM2SsdsInTwoSlotBoardPasses() {
        List<ToolBuildPart> parts = new ArrayList<>(List.of(
                motherboard(2), m2Ssd("ssd-1", 1), m2Ssd("ssd-2", 1)
        ));
        Map<String, Object> result = compatibility(parts);

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("m2SlotsChecked")).isEqualTo(true);
        assertThat(details(result).get("m2SlotsMatched")).isEqualTo(true);
    }

    @Test
    void boardWithoutM2SlotDataSkipsCheck() {
        // 보드에 m2Slots 데이터가 없으면(백필 전 유입) 검사를 생략한다 — 없는 데이터로 FAIL을 내지 않는다.
        List<ToolBuildPart> parts = new ArrayList<>(List.of(
                boardWithoutM2Data(), m2Ssd("ssd-1", 1), m2Ssd("ssd-2", 1), m2Ssd("ssd-3", 1)
        ));
        Map<String, Object> result = compatibility(parts);

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("m2Slots")).isNull();
        assertThat(details(result).get("m2SlotsChecked")).isEqualTo(false);
    }

    @Test
    void buildWithoutStorageDoesNotFail() {
        // SSD를 아직 안 담은 견적은 M.2 검사가 생략된다.
        List<ToolBuildPart> parts = new ArrayList<>(List.of(motherboard(2)));
        Map<String, Object> result = compatibility(parts);

        assertThat(result.get("status")).isEqualTo("PASS");
        assertThat(details(result).get("m2StorageTotal")).isEqualTo(0);
        assertThat(details(result).get("m2SlotsChecked")).isEqualTo(false);
    }
}

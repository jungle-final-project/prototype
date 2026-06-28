package com.buildgraph.prototype.rag;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;

public final class RagSeed {
    private RagSeed() {
    }

    public static Map<String, Object> search() {
        return MockData.map("items", List.of(
                MockData.map("id", "00000000-0000-4000-8000-000000004001", "sourceId", "psu-rule-001", "summary", "GPU 피크 전력과 CPU TDP 합산 후 여유율 적용", "score", 0.91),
                MockData.map("id", "00000000-0000-4000-8000-000000004002", "sourceId", "qhd-gaming-4070s", "summary", "QHD 게임 기준 GPU 우선 구성 근거", "score", 0.84)
        ), "page", 0, "size", 20, "total", 2);
    }

    public static Map<String, Object> evidence(String id) {
        return MockData.map(
                "id", id,
                "sourceId", "psu-rule-001",
                "summary", "GPU 피크 전력과 CPU TDP 합산 후 여유율 적용",
                "score", 0.91,
                "metadata", MockData.map("domain", "power", "owner", "agent-rag", "version", "seed")
        );
    }
}

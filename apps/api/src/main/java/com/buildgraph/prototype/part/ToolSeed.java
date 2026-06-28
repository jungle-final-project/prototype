package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.MockData;
import java.util.Map;

public final class ToolSeed {
    private ToolSeed() {
    }

    public static Map<String, Object> toolResult(String tool) {
        return MockData.map(
                "status", "compatibility".equals(tool) || "size".equals(tool) ? "PASS" : "WARN",
                "confidence", "MEDIUM",
                "summary", "Prototype seed result for " + tool,
                "details", MockData.map(
                        "checkedPartIds", java.util.List.of(PartSeed.GPU_ID),
                        "source", "seed",
                        "toolName", tool
                )
        );
    }
}

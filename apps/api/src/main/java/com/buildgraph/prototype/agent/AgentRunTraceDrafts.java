package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import java.math.BigDecimal;
import java.util.List;

final class AgentRunTraceDrafts {
    private AgentRunTraceDrafts() {
    }

    static AgentRagEvidenceDraft ragEvidence(AgentSessionRoot root, AgentRunProfile profile) {
        return switch (profile.purpose()) {
            case BUILD_RECOMMEND -> evidence(
                    "internal-rule-qhd-gaming-seed",
                    "QHD gaming recommendations prioritize GPU class, CPU balance, power margin, and current price.",
                    "QHD gaming build recommendation rule used by Agent runner.",
                    BigDecimal.valueOf(0.92),
                    root,
                    profile
            );
            case BUILD_EXPLAIN -> evidence(
                    "benchmark-build-explain-seed",
                    "Build explanations compare changed parts by expected bottleneck, price delta, and workload fit.",
                    "Benchmark and price reasoning used for build explanation.",
                    BigDecimal.valueOf(0.88),
                    root,
                    profile
            );
            case AS_ANALYZE -> evidence(
                    "support-guide-gpu-thermal-seed",
                    "Sustained GPU temperature spikes with frame time drops can indicate throttling or driver instability.",
                    "Troubleshooting evidence used for AS analysis.",
                    BigDecimal.valueOf(0.86),
                    root,
                    profile
            );
        };
    }

    static List<AgentToolInvocationDraft> toolInvocations(AgentSessionRoot root, AgentRunProfile profile) {
        return profile.toolNames().stream()
                .map(toolName -> toolInvocation(toolName, root, profile))
                .toList();
    }

    static String deterministicSummary(AgentRunProfile profile) {
        return switch (profile.purpose()) {
            case BUILD_RECOMMEND -> "Agent completed a build recommendation trace with RAG evidence and Tool checks.";
            case BUILD_EXPLAIN -> "Agent completed a build explanation trace with benchmark and price evidence.";
            case AS_ANALYZE -> "Agent completed an AS analysis trace with troubleshooting evidence and Tool checks.";
        };
    }

    private static AgentRagEvidenceDraft evidence(
            String sourceId,
            String chunkText,
            String summary,
            BigDecimal score,
            AgentSessionRoot root,
            AgentRunProfile profile
    ) {
        return new AgentRagEvidenceDraft(
                sourceId,
                chunkText,
                summary,
                score,
                MockData.map(
                        "sourceTypes", profile.ragSourceTypes(),
                        "purpose", profile.purpose().name(),
                        "rootType", root.type().name(),
                        "rootId", root.publicId(),
                        "retrievedAt", MockData.now()
                )
        );
    }

    private static AgentToolInvocationDraft toolInvocation(String toolName, AgentSessionRoot root, AgentRunProfile profile) {
        ToolStatus status = toolStatus(toolName, profile.purpose());
        ConfidenceLevel confidence = toolConfidence(toolName, profile.purpose());
        return new AgentToolInvocationDraft(
                toolName,
                status,
                confidence,
                toolSummary(toolName, status, profile.purpose()),
                MockData.map(
                        "toolName", toolName,
                        "rootType", root.type().name(),
                        "rootId", root.publicId(),
                        "purpose", profile.purpose().name(),
                        "context", MockData.map("summaryTarget", profile.summaryTarget())
                ),
                MockData.map(
                        "status", status.name(),
                        "confidence", confidence.name(),
                        "summary", toolSummary(toolName, status, profile.purpose()),
                        "details", MockData.map(
                                "deterministic", true,
                                "checkedAt", MockData.now(),
                                "evidenceSourceTypes", profile.ragSourceTypes()
                        )
                ),
                latencyMs(toolName)
        );
    }

    private static ToolStatus toolStatus(String toolName, AgentPurpose purpose) {
        if (purpose == AgentPurpose.AS_ANALYZE && "performance".equals(toolName)) {
            return ToolStatus.WARN;
        }
        if (purpose == AgentPurpose.BUILD_RECOMMEND && "price".equals(toolName)) {
            return ToolStatus.WARN;
        }
        return ToolStatus.PASS;
    }

    private static ConfidenceLevel toolConfidence(String toolName, AgentPurpose purpose) {
        if (purpose == AgentPurpose.AS_ANALYZE || "price".equals(toolName)) {
            return ConfidenceLevel.MEDIUM;
        }
        return ConfidenceLevel.HIGH;
    }

    private static String toolSummary(String toolName, ToolStatus status, AgentPurpose purpose) {
        return switch (purpose) {
            case BUILD_RECOMMEND -> "Seed " + toolName + " check for build recommendation returned " + status + ".";
            case BUILD_EXPLAIN -> "Seed " + toolName + " check for build explanation returned " + status + ".";
            case AS_ANALYZE -> "Seed " + toolName + " check for AS analysis returned " + status + ".";
        };
    }

    private static Integer latencyMs(String toolName) {
        List<String> order = List.of("compatibility", "power", "size", "performance", "price");
        int index = order.indexOf(toolName);
        return index < 0 ? 60 : 40 + (index * 11);
    }
}

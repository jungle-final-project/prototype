package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.common.MockData;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record PcAgentDiagnosisRequest(
        String diagnosisId,
        String deviceId,
        String symptom,
        List<String> requestedChecks,
        Instant requestedAt,
        Instant expiresAt,
        String mode
) {
    public Map<String, Object> toMap() {
        return MockData.map(
                "diagnosisId", diagnosisId,
                "deviceId", deviceId,
                "symptom", symptom,
                "requestedChecks", requestedChecks,
                "requestedAt", requestedAt.toString(),
                "expiresAt", expiresAt.toString(),
                "mode", mode
        );
    }
}

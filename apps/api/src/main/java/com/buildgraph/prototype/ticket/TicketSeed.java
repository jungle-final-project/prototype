package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;

public final class TicketSeed {
    private static final String TICKET_ID = "00000000-0000-4000-8000-000000006001";
    private static final String LOG_UPLOAD_ID = "00000000-0000-4000-8000-000000007001";

    private TicketSeed() {
    }

    public static List<Map<String, Object>> tickets() {
        return List.of(
                ticket(TICKET_ID, "OPEN"),
                ticket("00000000-0000-4000-8000-000000006002", "IN_PROGRESS")
        );
    }

    public static Map<String, Object> createTicket() {
        return ticket(TICKET_ID, "OPEN");
    }

    public static Map<String, Object> userTicket(String id) {
        return ticket(id, "OPEN");
    }

    public static Map<String, Object> adminTicket(String id) {
        return ticket(id, "OPEN");
    }

    private static Map<String, Object> ticket(String id, String status) {
        return MockData.map(
                "id", id,
                "status", status,
                "symptom", "게임 중 프레임 급락",
                "logUploadId", LOG_UPLOAD_ID,
                "assignedAdminId", null,
                "causeCandidates", List.of(MockData.map(
                        "code", "GPU_THERMAL",
                        "label", "GPU 온도 과열 가능성",
                        "confidence", "MEDIUM",
                        "evidenceIds", List.of("00000000-0000-4000-8000-000000004001")
                )),
                "upgradeCandidates", List.of(MockData.map(
                        "category", "CASE",
                        "reason", "케이스 쿨링 개선 후보",
                        "estimatedPrice", 90000
                )),
                "adminNote", null,
                "resolvedAt", null,
                "createdAt", MockData.now()
        );
    }
}

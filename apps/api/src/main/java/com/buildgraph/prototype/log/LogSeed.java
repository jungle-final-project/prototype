package com.buildgraph.prototype.log;

import com.buildgraph.prototype.common.MockData;
import java.util.Map;

public final class LogSeed {
    private static final String LOG_UPLOAD_ID = "00000000-0000-4000-8000-000000007001";

    private LogSeed() {
    }

    public static Map<String, Object> upload() {
        return detail(LOG_UPLOAD_ID);
    }

    public static Map<String, Object> detail(String id) {
        return MockData.map(
                "id", id,
                "status", "UPLOADED",
                "fileName", "agent-log.jsonl",
                "fileSize", 12000,
                "rangeMinutes", 30,
                "summary", "GPU 88도, 사용률 96%, VRAM 89% 관측",
                "createdAt", MockData.now(),
                "deleteAfter", "2026-07-29T10:40:00Z"
        );
    }
}

package com.buildgraph.prototype.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 스케줄 파이프라인 잡의 실행 이력을 pipeline_job_runs에 남기는 공용 기록기.
 *
 * 스케줄러가 결과 맵을 로그로만 남겨 실패가 관리자에게 보이지 않던 것(감사 O4)을,
 * 실행마다 상태·결과 요약·소요시간을 DB에 남겨 관리자 UI에서 조회 가능하게 한다.
 * 기록 실패가 잡 자체를 실패시키지 않도록 기록 오류는 삼키고 로그만 남긴다.
 */
@Component
public class PipelineJobRunRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger(PipelineJobRunRecorder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final JdbcTemplate jdbcTemplate;

    public PipelineJobRunRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 잡 본문을 감싸 성공/실패/소요시간을 기록한다. 예외는 기록 후 그대로 전파하지 않고 로그로 종결(스케줄 잡 관례 유지). */
    public void run(String jobName, Supplier<Map<String, Object>> body) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        long startNanos = System.nanoTime();
        try {
            Map<String, Object> result = body.get();
            insert(jobName, "SUCCEEDED", result, null, startedAt, elapsedMs(startNanos));
        } catch (RuntimeException exception) {
            insert(jobName, "FAILED", null, limited(exception.getMessage()), startedAt, elapsedMs(startNanos));
            LOGGER.warn("Pipeline job {} failed: {}", jobName, exception.getMessage());
        }
    }

    /** 데모 동결 등으로 실행을 건너뛴 사실도 이력에 남긴다(침묵 스킵 방지). */
    public void recordSkippedFrozen(String jobName) {
        OffsetDateTime now = OffsetDateTime.now();
        insert(jobName, "SKIPPED_FROZEN", null, "데모 동결(DEMO_FREEZE_MUTATIONS)로 실행을 건너뛰었습니다.", now, 0L);
    }

    public Map<String, Object> listRecent(Integer limit) {
        int safeLimit = Math.min(Math.max(limit == null ? 30 : limit, 1), 100);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT public_id::text AS id,
                       job_name,
                       trigger_type,
                       status,
                       result_summary,
                       error_summary,
                       started_at,
                       finished_at,
                       duration_ms
                FROM pipeline_job_runs
                ORDER BY created_at DESC, id DESC
                LIMIT ?
                """, safeLimit);
        List<Map<String, Object>> items = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            items.add(MockData.map(
                    "id", DbValueMapper.string(row, "id"),
                    "jobName", DbValueMapper.string(row, "job_name"),
                    "triggerType", DbValueMapper.string(row, "trigger_type"),
                    "status", DbValueMapper.string(row, "status"),
                    "resultSummary", DbValueMapper.json(row, "result_summary", null),
                    "errorSummary", DbValueMapper.string(row, "error_summary"),
                    "startedAt", DbValueMapper.timestamp(row, "started_at"),
                    "finishedAt", DbValueMapper.timestamp(row, "finished_at"),
                    "durationMs", row.get("duration_ms")
            ));
        }
        return MockData.map("items", items, "total", items.size());
    }

    private void insert(String jobName, String status, Map<String, Object> result, String errorSummary, OffsetDateTime startedAt, long durationMs) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO pipeline_job_runs (
                      job_name, trigger_type, status, result_summary, error_summary,
                      started_at, finished_at, duration_ms
                    )
                    VALUES (?, 'SCHEDULED', ?, ?::jsonb, ?, ?, now(), ?)
                    """,
                    jobName,
                    status,
                    result == null ? null : OBJECT_MAPPER.writeValueAsString(result),
                    errorSummary,
                    startedAt,
                    durationMs
            );
        } catch (Exception recordError) {
            LOGGER.warn("Pipeline job run 기록 실패 (job={}): {}", jobName, recordError.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static String limited(String value) {
        if (value == null) {
            return "unknown error";
        }
        return value.length() > 2000 ? value.substring(0, 2000) : value;
    }
}

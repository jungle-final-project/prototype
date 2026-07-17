package com.buildgraph.prototype.common;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {
    private final JdbcTemplate jdbcTemplate;
    // DB 판정 단기 캐시(single-flight 내장) — 헬스체크가 매번 풀 커넥션을 소모하면 풀 포화 시
    // 헬스가 먼저 죽어 부하테스트/LB가 서버를 조기 격리한다. DOWN(예외)은 캐시하지 않으므로
    // 장애 중에는 요청마다 재탐지되어 복구 감지가 늦어지지 않는다.
    private static final String DB_PROBE_KEY = "db";
    private final ReadThroughTtlCache<String, Boolean> dbProbeCache;

    public HealthController(
            JdbcTemplate jdbcTemplate,
            @Value("${buildgraph.health.db-probe-cache-ttl-ms:1000}") long dbProbeCacheTtlMs
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.dbProbeCache = new ReadThroughTtlCache<>(Duration.ofMillis(dbProbeCacheTtlMs), 4);
    }

    @GetMapping("/health")
    ResponseEntity<Map<String, Object>> health() {
        try {
            Boolean db = dbProbeCache.get(DB_PROBE_KEY, this::probeDatabase);
            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "database", Boolean.TRUE.equals(db) ? "UP" : "UNKNOWN"
            ));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("status", "DOWN"));
        }
    }

    private Boolean probeDatabase() {
        Integer db = jdbcTemplate.queryForObject("select 1", Integer.class);
        return Integer.valueOf(1).equals(db);
    }
}

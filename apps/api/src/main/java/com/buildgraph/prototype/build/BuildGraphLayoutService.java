package com.buildgraph.prototype.build;

import com.buildgraph.prototype.common.DbValueMapper;
import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.buildgraph.prototype.user.CurrentUserService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BuildGraphLayoutService {
    static final String DEFAULT_LAYOUT_KEY = "DEFAULT";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> ALLOWED_CATEGORIES = Set.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER", "PRICE"
    );
    // 3D 커넥터 앵커 대상 — 관계도 노드(PRICE 포함 9개)와 달리 실제 3D 글리프가 있는 8개만.
    private static final Set<String> ANCHOR_CATEGORIES = Set.of(
            "CPU", "MOTHERBOARD", "RAM", "GPU", "STORAGE", "PSU", "CASE", "COOLER"
    );
    private final JdbcTemplate jdbcTemplate;
    // DEFAULT 레이아웃은 전역 상수 성격(관리자만 변경)인데 resolve마다 SELECT+Jackson 파싱이 반복돼
    // 짧은 TTL로 캐시한다(single-flight 내장). '저장 레이아웃 없음'도 흔한 상태라 Optional로 부재까지 캐시.
    // 저장/초기화 시 remove로 즉시 무효화하므로 TTL은 다중 인스턴스 대비 신선도 상한일 뿐이다.
    private final ReadThroughTtlCache<String, Optional<SavedLayout>> layoutCache =
            new ReadThroughTtlCache<>(Duration.ofSeconds(30), 4);

    public BuildGraphLayoutService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<String, Object> getDefaultLayout() {
        SavedLayout savedLayout = savedLayout();
        if (savedLayout == null) {
            return layoutResponse("DEFAULT", defaultPositions(), Map.of(), null);
        }
        return layoutResponse("SAVED", mergeWithDefaults(savedLayout.positions()), savedLayout.anchors(), savedLayout.updatedAt());
    }

    public Map<String, GraphPosition> resolvePositions() {
        SavedLayout savedLayout = savedLayout();
        if (savedLayout == null) {
            return defaultPositions();
        }
        return mergeWithDefaults(savedLayout.positions());
    }

    public Map<String, Object> saveDefaultLayout(Map<String, Object> request, CurrentUserService.CurrentUser admin) {
        Map<String, GraphPosition> positions = normalizePositions(request == null ? Map.of() : request);
        Map<String, GraphAnchor> anchors = normalizeAnchors(request == null ? Map.of() : request);
        String json = toJson(positionMap(positions));
        String anchorsJson = toJson(anchorMap(anchors));
        jdbcTemplate.update("""
                INSERT INTO build_graph_layouts (
                  layout_key,
                  positions_json,
                  anchors_json,
                  created_by,
                  updated_by,
                  created_at,
                  updated_at
                )
                VALUES (?, ?::jsonb, ?::jsonb, ?, ?, now(), now())
                ON CONFLICT (layout_key) DO UPDATE
                SET positions_json = EXCLUDED.positions_json,
                    anchors_json = EXCLUDED.anchors_json,
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                """,
                DEFAULT_LAYOUT_KEY,
                json,
                anchorsJson,
                admin.internalId(),
                admin.internalId()
        );
        // 저장 직후 조회가 옛 캐시를 보지 않도록 즉시 무효화한다(TTL 만료 대기 금지).
        layoutCache.remove(DEFAULT_LAYOUT_KEY);
        return layoutResponse("SAVED", mergeWithDefaults(positions), anchors, null);
    }

    public Map<String, Object> resetDefaultLayout(CurrentUserService.CurrentUser admin) {
        jdbcTemplate.update("DELETE FROM build_graph_layouts WHERE layout_key = ?", DEFAULT_LAYOUT_KEY);
        layoutCache.remove(DEFAULT_LAYOUT_KEY);
        return layoutResponse("DEFAULT", defaultPositions(), Map.of(), null);
    }

    private SavedLayout savedLayout() {
        // 캐시 값은 불변으로 취급한다 — 소비처(mergeWithDefaults/layoutResponse)는 복사본만 만든다.
        return layoutCache.get(DEFAULT_LAYOUT_KEY, this::loadSavedLayout).orElse(null);
    }

    private Optional<SavedLayout> loadSavedLayout() {
        return jdbcTemplate.queryForList("""
                        SELECT positions_json::text AS positions_json,
                               anchors_json::text AS anchors_json,
                               updated_at
                        FROM build_graph_layouts
                        WHERE layout_key = ?
                        LIMIT 1
                        """, DEFAULT_LAYOUT_KEY)
                .stream()
                .findFirst()
                .map(row -> new SavedLayout(
                        parsePositions(DbValueMapper.string(row, "positions_json")),
                        parseAnchors(DbValueMapper.string(row, "anchors_json")),
                        DbValueMapper.timestamp(row, "updated_at")
                ));
    }

    private static Map<String, GraphPosition> normalizePositions(Map<String, Object> request) {
        Object value = request.get("positions");
        if (!(value instanceof Map<?, ?> rawPositions)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "positions가 필요합니다.");
        }
        Map<String, GraphPosition> positions = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawPositions.entrySet()) {
            String category = normalizeCategory(entry.getKey());
            if (!ALLOWED_CATEGORIES.contains(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 관계도 노드 category입니다.");
            }
            Map<String, Object> point = objectMap(entry.getValue());
            int x = coordinate(point.get("x"), "x");
            int y = coordinate(point.get("y"), "y");
            positions.put(category, new GraphPosition(x, y));
        }
        if (positions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "저장할 노드 좌표가 없습니다.");
        }
        return positions;
    }

    private static String normalizeCategory(Object value) {
        if (value == null || value.toString().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "category가 필요합니다.");
        }
        return value.toString().trim().toUpperCase(Locale.ROOT);
    }

    private static int coordinate(Object value, String key) {
        if (!(value instanceof Number) && value != null) {
            try {
                double parsed = Double.parseDouble(value.toString());
                return coordinate(parsed, key);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 좌표가 숫자가 아닙니다.");
            }
        }
        return coordinate(value == null ? Double.NaN : ((Number) value).doubleValue(), key);
    }

    private static int coordinate(double value, String key) {
        if (!Double.isFinite(value) || value < 0 || value > 2400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 좌표 범위가 올바르지 않습니다.");
        }
        return (int) Math.round(value);
    }

    private static Map<String, Object> objectMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, mapValue) -> {
                if (key != null) {
                    result.put(key.toString(), mapValue);
                }
            });
            return result;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "좌표는 x/y 객체여야 합니다.");
    }

    private static Map<String, GraphPosition> parsePositions(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return normalizePositions(Map.of("positions", parsed));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private static String toJson(Map<String, Object> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "관계도 배치 저장 데이터를 만들 수 없습니다.", exception);
        }
    }

    private static Map<String, GraphPosition> mergeWithDefaults(Map<String, GraphPosition> saved) {
        Map<String, GraphPosition> merged = defaultPositions();
        merged.putAll(saved);
        return merged;
    }

    private static Map<String, GraphPosition> defaultPositions() {
        Map<String, GraphPosition> positions = new LinkedHashMap<>();
        positions.put("CPU", new GraphPosition(20, 170));
        positions.put("MOTHERBOARD", new GraphPosition(300, 36));
        positions.put("RAM", new GraphPosition(640, 56));
        positions.put("GPU", new GraphPosition(300, 270));
        positions.put("PSU", new GraphPosition(640, 250));
        positions.put("CASE", new GraphPosition(640, 440));
        positions.put("COOLER", new GraphPosition(300, 500));
        positions.put("STORAGE", new GraphPosition(20, 650));
        positions.put("PRICE", new GraphPosition(300, 660));
        return positions;
    }

    private static Map<String, Object> layoutResponse(
            String source, Map<String, GraphPosition> positions, Map<String, GraphAnchor> anchors, Object updatedAt
    ) {
        return MockData.map(
                "layoutKey", DEFAULT_LAYOUT_KEY,
                "source", source,
                "positions", positionMap(positions),
                "anchors", anchorMap(anchors),
                "updatedAt", updatedAt
        );
    }

    private static Map<String, Object> positionMap(Map<String, GraphPosition> positions) {
        Map<String, Object> result = new LinkedHashMap<>();
        positions.forEach((category, position) -> result.put(category, position.toMap()));
        return result;
    }

    // anchors는 선택 필드 — 요청에 없으면 빈 맵(카드/자동 계산 폴백은 프론트에서 처리).
    private static Map<String, GraphAnchor> normalizeAnchors(Map<String, Object> request) {
        Object value = request.get("anchors");
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> rawAnchors)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "anchors는 객체여야 합니다.");
        }
        Map<String, GraphAnchor> anchors = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawAnchors.entrySet()) {
            String category = normalizeCategory(entry.getKey());
            if (!ANCHOR_CATEGORIES.contains(category)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "지원하지 않는 앵커 category입니다.");
            }
            Map<String, Object> anchor = objectMap(entry.getValue());
            anchors.put(category, new GraphAnchor(anchorPoint(anchor.get("card")), anchorPoint(anchor.get("part"))));
        }
        return anchors;
    }

    private static GraphPoint anchorPoint(Object value) {
        Map<String, Object> point = objectMap(value);
        return new GraphPoint(anchorCoordinate(point.get("x"), "x"), anchorCoordinate(point.get("y"), "y"));
    }

    // 앵커는 SVG viewBox 0~100 퍼센트 좌표라 슬롯 배치(0~2400)와 범위가 다르다.
    private static int anchorCoordinate(Object value, String key) {
        if (!(value instanceof Number) && value != null) {
            try {
                return anchorCoordinate(Double.parseDouble(value.toString()), key);
            } catch (NumberFormatException exception) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 앵커 좌표가 숫자가 아닙니다.");
            }
        }
        return anchorCoordinate(value == null ? Double.NaN : ((Number) value).doubleValue(), key);
    }

    private static int anchorCoordinate(double value, String key) {
        if (!Double.isFinite(value) || value < 0 || value > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, key + " 앵커 좌표 범위(0~100)가 올바르지 않습니다.");
        }
        return (int) Math.round(value);
    }

    private static Map<String, GraphAnchor> parseAnchors(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = OBJECT_MAPPER.readValue(json, MAP_TYPE);
            return normalizeAnchors(Map.of("anchors", parsed));
        } catch (JsonProcessingException exception) {
            return Map.of();
        }
    }

    private static Map<String, Object> anchorMap(Map<String, GraphAnchor> anchors) {
        Map<String, Object> result = new LinkedHashMap<>();
        anchors.forEach((category, anchor) -> result.put(category, anchor.toMap()));
        return result;
    }

    private record SavedLayout(Map<String, GraphPosition> positions, Map<String, GraphAnchor> anchors, Object updatedAt) {
    }

    public record GraphPosition(int x, int y) {
        Map<String, Object> toMap() {
            return Map.of("x", x, "y", y);
        }
    }

    public record GraphPoint(int x, int y) {
        Map<String, Object> toMap() {
            return Map.of("x", x, "y", y);
        }
    }

    public record GraphAnchor(GraphPoint card, GraphPoint part) {
        Map<String, Object> toMap() {
            return Map.of("card", card.toMap(), "part", part.toMap());
        }
    }
}

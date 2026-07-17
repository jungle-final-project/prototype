package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class BuildGraphLayoutServiceTest {
    private static final CurrentUserService.CurrentUser ADMIN = new CurrentUserService.CurrentUser(
            1L,
            "00000000-0000-4000-8000-000000000001",
            "admin@example.com",
            "Admin",
            "ADMIN",
            "2026-06-30T00:00:00Z"
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final BuildGraphLayoutService service = new BuildGraphLayoutService(jdbcTemplate);

    @Test
    void resolvePositionsReusesCachedLayoutRowWithinTtl() {
        when(jdbcTemplate.queryForList(anyString(), eq(BuildGraphLayoutService.DEFAULT_LAYOUT_KEY)))
                .thenReturn(List.of(layoutRow("{\"CPU\":{\"x\":880,\"y\":120}}")));

        Map<String, BuildGraphLayoutService.GraphPosition> first = service.resolvePositions();
        Map<String, BuildGraphLayoutService.GraphPosition> second = service.resolvePositions();

        assertThat(first.get("CPU")).isEqualTo(new BuildGraphLayoutService.GraphPosition(880, 120));
        assertThat(second.get("CPU")).isEqualTo(new BuildGraphLayoutService.GraphPosition(880, 120));
        // 전역 상수 성격의 DEFAULT 레이아웃 행은 TTL 안에서 SELECT+Jackson 파싱을 반복하지 않는다.
        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(BuildGraphLayoutService.DEFAULT_LAYOUT_KEY));
    }

    @Test
    void saveDefaultLayoutEvictsCacheSoNextReadSeesNewLayoutImmediately() {
        when(jdbcTemplate.queryForList(anyString(), eq(BuildGraphLayoutService.DEFAULT_LAYOUT_KEY)))
                // 저장 전: 저장 레이아웃 없음 — '부재'도 캐시되는 상태에서 무효화가 뚫리는지를 본다.
                .thenReturn(List.of())
                .thenReturn(List.of(layoutRow("{\"CPU\":{\"x\":880,\"y\":120}}")));

        assertThat(service.getDefaultLayout().get("source")).isEqualTo("DEFAULT");
        assertThat(service.getDefaultLayout().get("source")).isEqualTo("DEFAULT");
        verify(jdbcTemplate, times(1)).queryForList(anyString(), eq(BuildGraphLayoutService.DEFAULT_LAYOUT_KEY));

        service.saveDefaultLayout(Map.of("positions", Map.of("CPU", Map.of("x", 880, "y", 120))), ADMIN);

        // 저장 직후 조회는 TTL 만료를 기다리지 않고 새 레이아웃을 봐야 한다(즉시 무효화).
        Map<String, Object> after = service.getDefaultLayout();
        assertThat(after.get("source")).isEqualTo("SAVED");
        assertThat(castMap(castMap(after.get("positions")).get("CPU"))).isEqualTo(Map.of("x", 880, "y", 120));
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(BuildGraphLayoutService.DEFAULT_LAYOUT_KEY));
    }

    private static Map<String, Object> layoutRow(String positionsJson) {
        // Map.of는 null 값을 허용하지 않아 LinkedHashMap으로 만든다(anchors/updated_at 결측 행).
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("positions_json", positionsJson);
        row.put("anchors_json", null);
        row.put("updated_at", null);
        return row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}

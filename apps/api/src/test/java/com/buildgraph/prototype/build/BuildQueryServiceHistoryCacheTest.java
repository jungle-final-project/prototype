package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.buildgraph.prototype.agent.AgentJobPublisher;
import com.buildgraph.prototype.agent.AgentTraceService;
import com.buildgraph.prototype.agent.AiChatEngine;
import com.buildgraph.prototype.part.tool.ToolCheckService;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

// 내 견적함 목록 캐시의 핵심 계약 2가지를 고정한다:
// (1) TTL 내 재조회는 DB를 다시 치지 않는다, (2) 견적 mutation은 해당 유저 키를 즉시 무효화한다.
class BuildQueryServiceHistoryCacheTest {
    private static final CurrentUserService.CurrentUser USER = new CurrentUserService.CurrentUser(
            1004L,
            "00000000-0000-4000-8000-000000001004",
            "user@example.com",
            "Demo User",
            "USER",
            "2026-06-30T00:00:00Z"
    );
    private static final CurrentUserService.CurrentUser OTHER_USER = new CurrentUserService.CurrentUser(
            2001L,
            "00000000-0000-4000-8000-000000002001",
            "other@example.com",
            "Other User",
            "USER",
            "2026-06-30T00:00:00Z"
    );

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final BuildQueryService service = new BuildQueryService(
            jdbcTemplate,
            mock(AgentTraceService.class),
            mock(AgentJobPublisher.class),
            mock(AiChatEngine.class),
            mock(ToolCheckService.class),
            15L
    );

    @Test
    void buildsReusesCachedResultWithinTtlPerUser() {
        when(jdbcTemplate.queryForList(contains("LIMIT 30"), any(Object[].class))).thenReturn(List.of());

        assertThat(service.builds(USER)).isEmpty();
        assertThat(service.builds(USER)).isEmpty();
        // 같은 유저 TTL 내 재조회는 캐시 히트 — 상위 목록 쿼리가 1회만 나간다.
        verify(jdbcTemplate, times(1)).queryForList(contains("LIMIT 30"), any(Object[].class));

        assertThat(service.builds(OTHER_USER)).isEmpty();
        // 키는 userId — 다른 유저는 캐시를 공유하지 않는다.
        verify(jdbcTemplate, times(2)).queryForList(contains("LIMIT 30"), any(Object[].class));
    }

    @Test
    void deleteBuildEvictsHistoryCacheForOwner() {
        when(jdbcTemplate.queryForList(contains("LIMIT 30"), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.queryForList(contains("b.public_id = ?::uuid"), any(Object[].class)))
                .thenReturn(List.of(Map.of("id", "00000000-0000-4000-8000-000000009001")));

        service.builds(USER);
        service.deleteBuild("00000000-0000-4000-8000-000000009001", USER);
        service.builds(USER);

        // 삭제 mutation이 유저 키를 무효화해 다음 조회는 DB를 다시 친다.
        verify(jdbcTemplate, times(2)).queryForList(contains("LIMIT 30"), any(Object[].class));
    }
}

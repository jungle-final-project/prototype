package com.buildgraph.prototype.build;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 예산 티어(기본 200만~1300만원, 100만원 간격) 추천 조합을 백그라운드에서 미리 계산한다.
 * 서버 기동 직후 1회 + 고정 주기(기본 1시간, 가격 크론과 별개)로 갱신하며,
 * 계산 실패는 해당 티어만 건너뛰고 서버 동작에는 영향을 주지 않는다.
 */
@Service
public class BuildChatTierSnapshotRefresher {
    private static final Logger log = LoggerFactory.getLogger(BuildChatTierSnapshotRefresher.class);

    private final BuildChatService buildChatService;
    private final BuildChatTierSnapshotStore store;
    private final boolean enabled;
    private final int minBudgetWon;
    private final int maxBudgetWon;
    private final int stepWon;

    public BuildChatTierSnapshotRefresher(
            BuildChatService buildChatService,
            BuildChatTierSnapshotStore store,
            @Value("${ai.build-chat.tier-snapshot.enabled:true}") boolean enabled,
            @Value("${ai.build-chat.tier-snapshot.min-budget-won:2000000}") int minBudgetWon,
            @Value("${ai.build-chat.tier-snapshot.max-budget-won:13000000}") int maxBudgetWon,
            @Value("${ai.build-chat.tier-snapshot.step-won:1000000}") int stepWon
    ) {
        this.buildChatService = buildChatService;
        this.store = store;
        this.enabled = enabled;
        this.minBudgetWon = minBudgetWon;
        this.maxBudgetWon = maxBudgetWon;
        this.stepWon = Math.max(100_000, stepWon);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void refreshAfterReady() {
        if (!enabled) {
            log.info("Build Chat tier snapshot refresh skipped: disabled");
            return;
        }
        CompletableFuture.runAsync(this::refresh);
    }

    @Scheduled(fixedDelayString = "${ai.build-chat.tier-snapshot.refresh-delay-ms:3600000}",
            initialDelayString = "${ai.build-chat.tier-snapshot.refresh-delay-ms:3600000}")
    public void refreshOnSchedule() {
        if (!enabled) {
            return;
        }
        refresh();
    }

    private void refresh() {
        long startedNanos = System.nanoTime();
        int refreshed = 0;
        for (int tier = minBudgetWon; tier <= maxBudgetWon; tier += stepWon) {
            try {
                BuildChatService.TierBuilds tierBuilds = buildChatService.computeBudgetTierBuilds(tier);
                if (tierBuilds.builds().isEmpty()) {
                    log.warn("Build Chat tier snapshot empty: tierBudgetWon={}", tier);
                    continue;
                }
                store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                        tier,
                        List.copyOf(tierBuilds.builds()),
                        List.copyOf(tierBuilds.warnings()),
                        Instant.now()
                ));
                refreshed += 1;
            } catch (Exception error) {
                log.warn("Build Chat tier snapshot failed: tierBudgetWon={}, error={}", tier, error.getMessage());
            }
        }
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000;
        log.info("Build Chat tier snapshots refreshed: tiers={}, stored={}, elapsedMs={}", refreshed, store.size(), elapsedMs);
    }

    // 테스트/운영 진단용: 강제 재계산
    public void forceRefresh() {
        refresh();
    }
}

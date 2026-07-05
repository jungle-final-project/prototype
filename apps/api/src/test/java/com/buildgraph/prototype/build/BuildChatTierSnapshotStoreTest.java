package com.buildgraph.prototype.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BuildChatTierSnapshotStoreTest {
    private static BuildChatTierSnapshotStore storeWithTiers(int... tiers) {
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        for (int tier : tiers) {
            store.put(new BuildChatTierSnapshotStore.TierSnapshot(
                    tier,
                    List.of(Map.of("id", "tier-" + tier)),
                    List.of(),
                    Instant.now()
            ));
        }
        return store;
    }

    @Test
    void picksLargestTierAtOrBelowBudgetWithinTolerance() {
        BuildChatTierSnapshotStore store = storeWithTiers(2_000_000, 3_000_000, 4_000_000);

        assertThat(store.bestFor(4_370_000, "TARGET", 15).orElseThrow().tierBudgetWon()).isEqualTo(4_000_000);
        assertThat(store.bestFor(3_000_000, "MAX", 15).orElseThrow().tierBudgetWon()).isEqualTo(3_000_000);
        assertThat(store.bestFor(3_050_000, "MAX", 15).orElseThrow().tierBudgetWon()).isEqualTo(3_000_000);
    }

    @Test
    void fallsThroughWhenBudgetGapExceedsTolerance() {
        BuildChatTierSnapshotStore store = storeWithTiers(2_000_000);

        // 290만원 요청 vs 200만원 티어 → 31% 차이라 즉시 제공하지 않는다
        assertThat(store.bestFor(2_900_000, "TARGET", 15)).isEmpty();
        // 예산이 최상위 티어보다 훨씬 크면 폴백
        assertThat(store.bestFor(20_000_000, "TARGET", 15)).isEmpty();
    }

    @Test
    void doesNotServeMinModeOrBudgetBelowLowestTier() {
        BuildChatTierSnapshotStore store = storeWithTiers(2_000_000, 3_000_000);

        // "이상" 모드는 티어 총액이 하한을 보장하지 못하므로 미지원
        assertThat(store.bestFor(2_500_000, "MIN", 15)).isEmpty();
        assertThat(store.bestFor(1_500_000, "TARGET", 15)).isEmpty();
        assertThat(store.bestFor(null, "TARGET", 15)).isEmpty();
    }

    @Test
    void ignoresEmptySnapshots() {
        BuildChatTierSnapshotStore store = new BuildChatTierSnapshotStore();
        store.put(new BuildChatTierSnapshotStore.TierSnapshot(2_000_000, List.of(), List.of(), Instant.now()));

        assertThat(store.size()).isZero();
        assertThat(store.bestFor(2_000_000, "TARGET", 15)).isEmpty();
    }
}

package com.buildgraph.prototype.build;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentSkipListMap;
import org.springframework.stereotype.Service;

/**
 * 예산 티어별로 미리 계산해 둔 추천 조합 스냅샷의 인메모리 저장소.
 * 티어 조합은 부품/가격 데이터 갱신 주기에 맞춰 백그라운드에서 재계산되며,
 * 요청 예산과 티어의 차이가 허용 오차 안일 때만 즉시 응답에 사용한다.
 */
@Service
public class BuildChatTierSnapshotStore {
    /** 재계산이 반복 실패해도 낡은 스냅샷을 무기한 서빙하지 않도록 하는 나이 상한. 초과 시 빈 값으로 폴백한다. */
    static final Duration MAX_SNAPSHOT_AGE = Duration.ofHours(24);

    public record TierSnapshot(
            int tierBudgetWon,
            List<Map<String, Object>> builds,
            List<String> warnings,
            Instant computedAt
    ) {
    }

    private final ConcurrentSkipListMap<Integer, TierSnapshot> snapshots = new ConcurrentSkipListMap<>();

    public void put(TierSnapshot snapshot) {
        if (snapshot == null || snapshot.builds() == null || snapshot.builds().isEmpty()) {
            return;
        }
        snapshots.put(snapshot.tierBudgetWon(), snapshot);
    }

    public int size() {
        return snapshots.size();
    }

    /**
     * MAX(이하)/TARGET 모드 예산 요청에 쓸 수 있는 가장 큰 하위 티어를 찾는다.
     * MIN(이상) 모드는 티어 총액이 요청 하한을 보장하지 못하므로 지원하지 않는다.
     * 요청 예산과 티어의 차이가 예산의 tolerancePct%를 넘으면 빈 값을 반환해
     * 기존 deterministic/LLM 경로로 폴백하게 한다.
     * 재계산이 반복 실패해 computedAt이 {@link #MAX_SNAPSHOT_AGE}를 넘은 스냅샷도 빈 값으로 폴백한다.
     */
    public Optional<TierSnapshot> bestFor(Integer budgetWon, String mode, double tolerancePct) {
        if (budgetWon == null || budgetWon <= 0 || "MIN".equals(mode)) {
            return Optional.empty();
        }
        Map.Entry<Integer, TierSnapshot> floor = snapshots.floorEntry(budgetWon);
        if (floor == null) {
            return Optional.empty();
        }
        long diff = (long) budgetWon - floor.getKey();
        if (diff > budgetWon * (tolerancePct / 100.0)) {
            return Optional.empty();
        }
        TierSnapshot snapshot = floor.getValue();
        if (snapshot.computedAt() == null
                || snapshot.computedAt().isBefore(Instant.now().minus(MAX_SNAPSHOT_AGE))) {
            return Optional.empty();
        }
        return Optional.of(snapshot);
    }
}

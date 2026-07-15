package com.buildgraph.prototype.common;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 사용자와 무관한 읽기 전용 응답을 짧은 TTL 동안 재사용하는 최소 인메모리 캐시(단일 인스턴스 전제).
 *
 * <ul>
 *   <li>ttl &le; 0이면 캐시를 끄고 항상 loader를 호출한다(캐시 비활성화 스위치).</li>
 *   <li>maxSize를 넘으면 통째로 비운다 — 짧은 TTL·단일 인스턴스라 정교한 축출 대신 단순·안전을 택한다.</li>
 *   <li>캐시 값은 불변으로 취급한다 — 호출처는 캐시된 응답을 변형하지 않는다(직렬화 후 그대로 반환).</li>
 * </ul>
 *
 * 신선도는 TTL로만 보장한다. 가격/부품 데이터는 일일 갱신 주기라 수십 초 지연은 무시 가능하며,
 * 더 강한 즉시 무효화가 필요하면 mutation 경로에서 {@link #clear()}를 호출한다.
 */
public final class ReadThroughTtlCache<K, V> {
    private record Entry<V>(V value, long expiresAtNanos) {
    }

    private final Map<K, Entry<V>> store = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final int maxSize;

    public ReadThroughTtlCache(Duration ttl, int maxSize) {
        this.ttlNanos = ttl == null ? 0L : Math.max(0L, ttl.toNanos());
        this.maxSize = Math.max(1, maxSize);
    }

    /** 키가 살아있으면 캐시 값을, 아니면 loader로 계산해 캐시에 담고 반환한다. loader 결과가 null이면 캐시하지 않는다. */
    public V get(K key, Supplier<V> loader) {
        if (ttlNanos <= 0L) {
            return loader.get();
        }
        long now = System.nanoTime();
        Entry<V> hit = store.get(key);
        if (hit != null && hit.expiresAtNanos() > now) {
            return hit.value();
        }
        V value = loader.get();
        if (value == null) {
            return null;
        }
        if (store.size() >= maxSize) {
            store.clear();
        }
        store.put(key, new Entry<>(value, now + ttlNanos));
        return value;
    }

    public void clear() {
        store.clear();
    }
}

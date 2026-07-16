package com.buildgraph.prototype.config.cache;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "caffeine", matchIfMissing = true)
    public CaffeineCacheManager caffeineCacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(caffeineCacheBuilder());
        manager.setAsyncCacheMode(true);
        return manager;
    }

    /* 원본과 같은 기준으로 캐시 생명은 10분, 최대 저장 크기는 1,000개로 둔다. */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "caffeine", matchIfMissing = true)
    public Caffeine<Object, Object> caffeineCacheBuilder() {
        return Caffeine.newBuilder()
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .maximumSize(1_000);
    }

    /* k6 non-cache 비교에서는 같은 조회 코드를 타되 저장만 수행하지 않는다. */
    @Bean
    @ConditionalOnProperty(name = "spring.cache.type", havingValue = "none")
    public CacheManager noOpCacheManager() {
        return new NoOpCacheManager();
    }
}

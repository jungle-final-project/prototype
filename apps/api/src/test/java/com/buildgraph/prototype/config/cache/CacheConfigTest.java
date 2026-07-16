package com.buildgraph.prototype.config.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NoOpCacheManager;

class CacheConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(CacheConfig.class);

    @Test
    void noneUsesNoOpCacheManager() {
        contextRunner.withPropertyValues("spring.cache.type=none").run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(NoOpCacheManager.class);
        });
    }

    @Test
    void caffeineUsesCaffeineCacheManager() {
        contextRunner.withPropertyValues("spring.cache.type=caffeine").run(context -> {
            assertThat(context).hasSingleBean(CacheManager.class);
            assertThat(context.getBean(CacheManager.class)).isInstanceOf(CaffeineCacheManager.class);
        });
    }
}

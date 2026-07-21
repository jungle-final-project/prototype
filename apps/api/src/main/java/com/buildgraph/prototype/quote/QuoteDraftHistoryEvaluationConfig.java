package com.buildgraph.prototype.quote;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
class QuoteDraftHistoryEvaluationConfig {

    @Bean(name = "quoteDraftHistoryEvaluationExecutor", destroyMethod = "shutdown")
    ThreadPoolTaskScheduler quoteDraftHistoryEvaluationExecutor(
            @Value("${buildgraph.quote-draft-history.evaluation.executor-pool-size:1}") int poolSize,
            @Value("${buildgraph.quote-draft-history.evaluation.executor-shutdown-wait-seconds:5}") int shutdownWaitSeconds
    ) {
        ThreadPoolTaskScheduler executor = new ThreadPoolTaskScheduler();
        executor.setThreadNamePrefix("quote-history-evaluation-");
        executor.setPoolSize(Math.max(1, Math.min(4, poolSize)));
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setRemoveOnCancelPolicy(true);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(Math.max(0, shutdownWaitSeconds));
        executor.initialize();
        return executor;
    }
}

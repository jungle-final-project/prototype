package com.buildgraph.prototype.quote;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

class QuoteDraftHistoryEvaluationDispatcherTest {
    private ThreadPoolTaskScheduler executor;
    private QuoteDraftHistoryEvaluationWorker worker;
    private QuoteDraftHistoryEvaluationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolTaskScheduler();
        executor.setPoolSize(1);
        executor.setThreadNamePrefix("quote-history-test-");
        executor.initialize();
        worker = mock(QuoteDraftHistoryEvaluationWorker.class);
        when(worker.nextEvaluationDelayMs()).thenReturn(null);
        dispatcher = new QuoteDraftHistoryEvaluationDispatcher(worker, executor);
    }

    @AfterEach
    void tearDown() {
        executor.shutdown();
    }

    @Test
    void commitEventRunsEvaluationWithoutGlobalScheduledInfrastructure() {
        dispatcher.onHistoryCreated(new QuoteDraftHistoryEvaluationRequested(7L));

        verify(worker, timeout(1000)).drainReadyEvaluations();
        verify(worker, timeout(1000)).nextEvaluationDelayMs();
    }

    @Test
    void applicationStartupRecoversPendingEvaluation() {
        dispatcher.onApplicationReady();

        verify(worker, timeout(1000)).drainReadyEvaluations();
    }
}

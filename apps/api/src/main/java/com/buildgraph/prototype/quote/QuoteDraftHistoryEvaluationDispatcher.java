package com.buildgraph.prototype.quote;

import java.time.Instant;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class QuoteDraftHistoryEvaluationDispatcher {
    private static final Logger log = LoggerFactory.getLogger(QuoteDraftHistoryEvaluationDispatcher.class);
    private static final long MIN_WAKEUP_DELAY_MS = 25;

    private final QuoteDraftHistoryEvaluationWorker worker;
    private final ThreadPoolTaskScheduler executor;
    private final AtomicBoolean requested = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<ScheduledFuture<?>> retryWakeup = new AtomicReference<>();

    public QuoteDraftHistoryEvaluationDispatcher(
            QuoteDraftHistoryEvaluationWorker worker,
            @Qualifier("quoteDraftHistoryEvaluationExecutor") ThreadPoolTaskScheduler executor
    ) {
        this.worker = worker;
        this.executor = executor;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onHistoryCreated(QuoteDraftHistoryEvaluationRequested ignored) {
        kick();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        kick();
    }

    void kick() {
        requested.set(true);
        cancelRetryWakeup();
        startIfIdle();
    }

    private void startIfIdle() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(this::run);
        } catch (RejectedExecutionException exception) {
            running.set(false);
            log.warn("견적 변경 기록 평가 executor가 작업을 수락하지 못했습니다.", exception);
        }
    }

    private void run() {
        Long nextDelayMs = null;
        try {
            do {
                requested.set(false);
                worker.drainReadyEvaluations();
            } while (requested.get());
            nextDelayMs = worker.nextEvaluationDelayMs();
        } catch (RuntimeException exception) {
            log.warn("견적 변경 기록 평가 dispatcher 실행에 실패했습니다.", exception);
            nextDelayMs = 1000L;
        } finally {
            running.set(false);
        }

        if (requested.get()) {
            startIfIdle();
            return;
        }
        if (nextDelayMs != null) {
            scheduleWakeup(nextDelayMs);
        }
    }

    private void scheduleWakeup(long delayMs) {
        long boundedDelayMs = Math.max(MIN_WAKEUP_DELAY_MS, delayMs);
        try {
            ScheduledFuture<?> future = executor.schedule(
                    this::kick,
                    Instant.now().plusMillis(boundedDelayMs)
            );
            ScheduledFuture<?> previous = retryWakeup.getAndSet(future);
            if (previous != null && !previous.isDone()) {
                previous.cancel(false);
            }
        } catch (RejectedExecutionException exception) {
            log.warn("견적 변경 기록 평가 재시도 예약에 실패했습니다.", exception);
        }
    }

    private void cancelRetryWakeup() {
        ScheduledFuture<?> future = retryWakeup.getAndSet(null);
        if (future != null && !future.isDone()) {
            future.cancel(false);
        }
    }
}

record QuoteDraftHistoryEvaluationRequested(long draftId) {
}

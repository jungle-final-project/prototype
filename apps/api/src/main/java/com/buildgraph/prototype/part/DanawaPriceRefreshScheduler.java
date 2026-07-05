package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import com.buildgraph.prototype.common.PipelineJobRunRecorder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.danawa-refresh", name = "enabled", havingValue = "true")
public class DanawaPriceRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DanawaPriceRefreshScheduler.class);

    private final DanawaPriceSnapshotService danawaPriceSnapshotService;
    private final DemoFreezeGuard demoFreezeGuard;
    private final PipelineJobRunRecorder jobRunRecorder;

    public DanawaPriceRefreshScheduler(
            DanawaPriceSnapshotService danawaPriceSnapshotService,
            DemoFreezeGuard demoFreezeGuard,
            PipelineJobRunRecorder jobRunRecorder
    ) {
        this.danawaPriceSnapshotService = danawaPriceSnapshotService;
        this.demoFreezeGuard = demoFreezeGuard;
        this.jobRunRecorder = jobRunRecorder;
    }

    @Scheduled(cron = "${part.danawa-refresh.cron:0 30 4 * * *}", zone = "${part.danawa-refresh.zone:Asia/Seoul}")
    public void refreshDailyDanawaSnapshots() {
        if (demoFreezeGuard.frozen()) {
            LOGGER.info("Daily Danawa backup price snapshot refresh skipped: demo freeze is on");
            jobRunRecorder.recordSkippedFrozen("DANAWA_SNAPSHOT_REFRESH");
            return;
        }
        jobRunRecorder.run("DANAWA_SNAPSHOT_REFRESH", () -> {
            Map<String, Object> result = danawaPriceSnapshotService.refreshDailySnapshots();
            LOGGER.info("Daily Danawa backup price snapshot refresh finished: {}", result);
            return result;
        });
    }
}

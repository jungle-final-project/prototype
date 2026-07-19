package com.buildgraph.prototype.part.price;

import com.buildgraph.prototype.common.DemoFreezeGuard;
import com.buildgraph.prototype.common.PipelineJobRunRecorder;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "part.danawa-trend-refresh", name = "enabled", havingValue = "true")
public class DanawaPriceTrendRefreshScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(DanawaPriceTrendRefreshScheduler.class);

    private final DanawaPriceTrendService danawaPriceTrendService;
    private final DemoFreezeGuard demoFreezeGuard;
    private final PipelineJobRunRecorder jobRunRecorder;

    public DanawaPriceTrendRefreshScheduler(
            DanawaPriceTrendService danawaPriceTrendService,
            DemoFreezeGuard demoFreezeGuard,
            PipelineJobRunRecorder jobRunRecorder
    ) {
        this.danawaPriceTrendService = danawaPriceTrendService;
        this.demoFreezeGuard = demoFreezeGuard;
        this.jobRunRecorder = jobRunRecorder;
    }

    @Scheduled(cron = "${part.danawa-trend-refresh.cron:0 30 5 1 * *}", zone = "${part.danawa-trend-refresh.zone:Asia/Seoul}")
    public void refreshMonthlyDanawaPriceTrends() {
        if (demoFreezeGuard.frozen()) {
            LOGGER.info("Monthly Danawa price trend refresh skipped: demo freeze is on");
            jobRunRecorder.recordSkippedFrozen("DANAWA_TREND_REFRESH");
            return;
        }
        jobRunRecorder.run("DANAWA_TREND_REFRESH", () -> {
            Map<String, Object> result = danawaPriceTrendService.refreshMonthlyTrends();
            LOGGER.info("Monthly Danawa price trend refresh finished: {}", result);
            return result;
        });
    }
}

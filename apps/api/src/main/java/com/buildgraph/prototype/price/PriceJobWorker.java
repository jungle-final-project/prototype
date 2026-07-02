package com.buildgraph.prototype.price;

import com.buildgraph.prototype.common.RabbitQueueConfig;
import java.util.Map;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PriceJobWorker {
    private final PriceQueryService priceQueryService;

    public PriceJobWorker(PriceQueryService priceQueryService) {
        this.priceQueryService = priceQueryService;
    }

    @RabbitListener(queues = RabbitQueueConfig.PRICE_REFRESH_QUEUE)
    public void runPriceRefresh(Map<String, Object> payload) {
        String priceJobId = requiredText(payload.get("priceJobId"));
        try {
            priceQueryService.startPriceJob(priceJobId);
            priceQueryService.completePriceJob(priceJobId);
        } catch (RuntimeException error) {
            priceQueryService.failPriceJob(priceJobId, safeReason(error));
            throw error;
        }
    }

    private static String requiredText(Object value) {
        String text = value == null ? null : String.valueOf(value).trim();
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("priceJobId가 필요합니다.");
        }
        return text;
    }

    private static String safeReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}

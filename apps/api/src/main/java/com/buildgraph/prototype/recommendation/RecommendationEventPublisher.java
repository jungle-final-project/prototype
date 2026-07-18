package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.RabbitQueueConfig;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RecommendationEventPublisher {
    private final RabbitTemplate rabbitTemplate;

    public RecommendationEventPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public Map<String, Object> publishBulkEvents(
            Map<String, Object> request,
            CurrentUserService.CurrentUser user,
            int queuedCount
    ) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitQueueConfig.JOBS_EXCHANGE,
                    RabbitQueueConfig.RECOMMENDATION_EVENTS_ROUTING_KEY,
                    MockData.map(
                            "request", request,
                            "user", userPayload(user)
                    )
            );
            return MockData.map("accepted", true, "queued", queuedCount);
        } catch (AmqpException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Recommendation event queue is unavailable.", error);
        }
    }

    private static Map<String, Object> userPayload(CurrentUserService.CurrentUser user) {
        return MockData.map(
                "internalId", user.internalId(),
                "id", user.id(),
                "email", user.email(),
                "name", user.name(),
                "role", user.role(),
                "createdAt", user.createdAt()
        );
    }
}

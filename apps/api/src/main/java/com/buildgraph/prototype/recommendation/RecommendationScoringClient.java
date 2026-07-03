package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RecommendationScoringClient {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final HttpClient httpClient;
    private final String endpoint;

    public RecommendationScoringClient(
            @Value("${recommendation.reranker.endpoint:http://localhost:8091/score}") String endpoint,
            @Value("${recommendation.reranker.timeout-ms:1200}") long timeoutMs
    ) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(100, timeoutMs)))
                .build();
    }

    public Map<String, Object> score(Map<String, Object> payload) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(3))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("scorer returned HTTP " + response.statusCode());
        }
        return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
    }

    public Map<String, Object> reload(String modelPath) {
        try {
            HttpRequest request = HttpRequest.newBuilder(reloadUri())
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            OBJECT_MAPPER.writeValueAsString(MockData.map("modelPath", modelPath)),
                            StandardCharsets.UTF_8
                    ))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("scorer reload returned HTTP " + response.statusCode());
            }
            return OBJECT_MAPPER.readValue(response.body(), MAP_TYPE);
        } catch (Exception error) {
            throw new IllegalStateException("scorer reload failed: " + error.getMessage(), error);
        }
    }

    public Map<String, Object> payload(
            String requestHash,
            String profile,
            boolean activeRerankEnabled,
            List<Map<String, Object>> candidates
    ) {
        return MockData.map(
                "requestHash", requestHash,
                "profile", profile,
                "activeRerankEnabled", activeRerankEnabled,
                "candidates", candidates
        );
    }

    private URI reloadUri() {
        URI scoreUri = URI.create(endpoint);
        String path = scoreUri.getPath();
        String reloadPath = path == null || path.isBlank() || "/".equals(path)
                ? "/reload"
                : path.replaceFirst("/score$", "/reload");
        if (reloadPath.equals(path)) {
            reloadPath = "/reload";
        }
        return URI.create(scoreUri.getScheme() + "://" + scoreUri.getAuthority() + reloadPath);
    }
}

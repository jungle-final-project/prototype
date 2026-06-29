package com.buildgraph.prototype.part;

import com.buildgraph.prototype.common.MockData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Service
public class NaverShoppingOfferService {
    private final String clientId;
    private final String clientSecret;
    private final RestClient restClient;
    private final ConcurrentHashMap<String, Optional<Map<String, Object>>> cache = new ConcurrentHashMap<>();

    public NaverShoppingOfferService(
            @Value("${naver.search.client-id:}") String clientId,
            @Value("${naver.search.client-secret:}") String clientSecret,
            @Value("${naver.search.base-url:https://openapi.naver.com}") String baseUrl
    ) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    Optional<Map<String, Object>> offerFor(String partId, String name, String manufacturer) {
        if (!configured() || !StringUtils.hasText(name)) {
            return Optional.empty();
        }
        String cacheKey = partId + "::" + name;
        return cache.computeIfAbsent(cacheKey, ignored -> fetchOffer(searchQuery(name, manufacturer)));
    }

    private Optional<Map<String, Object>> fetchOffer(String query) {
        try {
            Map<?, ?> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v1/search/shop.json")
                            .queryParam("query", query)
                            .queryParam("display", 1)
                            .queryParam("start", 1)
                            .queryParam("sort", "sim")
                            .queryParam("exclude", "used:rental:cbshop")
                            .build())
                    .header("X-Naver-Client-Id", clientId)
                    .header("X-Naver-Client-Secret", clientSecret)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !(response.get("items") instanceof List<?> items) || items.isEmpty()) {
                return Optional.empty();
            }
            if (!(items.get(0) instanceof Map<?, ?> item)) {
                return Optional.empty();
            }

            return Optional.of(MockData.map(
                    "title", cleanText(stringValue(item.get("title"))),
                    "imageUrl", stringValue(item.get("image")),
                    "supplierName", stringValue(item.get("mallName")),
                    "offerUrl", stringValue(item.get("link")),
                    "lowPrice", integerValue(item.get("lprice")),
                    "source", "NAVER_SHOPPING_SEARCH"
            ));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private boolean configured() {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }

    private static String searchQuery(String name, String manufacturer) {
        if (!StringUtils.hasText(manufacturer) || manufacturer.endsWith("Partner")) {
            return name;
        }
        return manufacturer + " " + name;
    }

    private static String cleanText(String value) {
        if (value == null) {
            return null;
        }
        return value
                .replaceAll("<[^>]+>", "")
                .replace("&quot;", "\"")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private static String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer integerValue(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

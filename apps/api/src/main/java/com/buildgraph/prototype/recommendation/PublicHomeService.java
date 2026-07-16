package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import com.buildgraph.prototype.part.catalog.PartQueryService;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PublicHomeService {
    private static final List<String> CATEGORIES =
            List.of("CPU", "GPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE", "COOLER");
    private static final String CACHE_KEY = "home";
    private final PartQueryService partQueryService;
    private final HomePartRecommendationService homePartRecommendationService;
    // 홈 응답은 사용자 무관(카테고리 목록 + 공용 추천)이라 통째로 짧은 TTL 캐시한다 —
    // 매 요청 8카테고리 조회 + 추천 후보 전수 스캔을 반복하지 않는다.
    private final ReadThroughTtlCache<String, Map<String, Object>> homeCache;

    public PublicHomeService(
            PartQueryService partQueryService,
            HomePartRecommendationService homePartRecommendationService,
            @Value("${recommendation.home-cache.ttl-seconds:30}") long cacheTtlSeconds
    ) {
        this.partQueryService = partQueryService;
        this.homePartRecommendationService = homePartRecommendationService;
        this.homeCache = new ReadThroughTtlCache<>(Duration.ofSeconds(cacheTtlSeconds), 4);
    }

    public Map<String, Object> home() {
        return homeCache.get(CACHE_KEY, this::computeHome);
    }

    private Map<String, Object> computeHome() {
        Map<String, Object> categoryParts = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            Map<String, Object> page = partQueryService.parts(
                    category, null, null, "ACTIVE", null, null, 0, 4, "price_desc"
            );
            categoryParts.put(category, page.getOrDefault("items", List.of()));
        }
        return MockData.map(
                "categoryParts", categoryParts,
                "recommendedParts", homePartRecommendationService.publicHomeParts(5)
        );
    }
}
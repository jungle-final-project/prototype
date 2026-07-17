package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.common.ReadThroughTtlCache;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class PublicHomeService {
    private static final String CACHE_KEY = "home";

    private final HomeCategoryPartsService homeCategoryPartsService;
    private final HomePartRecommendationService homePartRecommendationService;
    private final ReadThroughTtlCache<String, Map<String, Object>> homeCache;

    public PublicHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService,
            @Value("${recommendation.home-cache.ttl-seconds:30}") long cacheTtlSeconds
    ) {
        this.homeCategoryPartsService = homeCategoryPartsService;
        this.homePartRecommendationService = homePartRecommendationService;
        this.homeCache = new ReadThroughTtlCache<>(Duration.ofSeconds(cacheTtlSeconds), 4);
    }

    public Map<String, Object> home() {
        return homeCache.get(CACHE_KEY, this::computeHome);
    }

    private Map<String, Object> computeHome() {
        return MockData.map(
                "categoryParts", homeCategoryPartsService.priceDescCategoryParts(),
                "recommendedParts", homePartRecommendationService.publicHomeParts(5)
        );
    }
}

package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.common.MockData;
import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class AuthenticatedHomeService {
    public static final String CACHE_NAME = "home-authenticated";

    private final HomeCategoryPartsService homeCategoryPartsService;
    private final HomePartRecommendationService homePartRecommendationService;

    public AuthenticatedHomeService(
            HomeCategoryPartsService homeCategoryPartsService,
            HomePartRecommendationService homePartRecommendationService
    ) {
        this.homeCategoryPartsService = homeCategoryPartsService;
        this.homePartRecommendationService = homePartRecommendationService;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "'authenticated-home:v1'")
    public Map<String, Object> home(CurrentUserService.CurrentUser user) {
        return MockData.map(
                "categoryParts", homeCategoryPartsService.priceDescCategoryParts(),
                "recommendedParts", homePartRecommendationService.sharedHomeParts(5)
        );
    }
}

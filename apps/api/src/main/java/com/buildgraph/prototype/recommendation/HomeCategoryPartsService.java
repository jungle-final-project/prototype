package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.part.catalog.PartQueryService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class HomeCategoryPartsService {
    public static final String CACHE_NAME = "home-category-parts";
    private static final List<String> CATEGORIES =
            List.of("CPU", "GPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE", "COOLER");

    private final PartQueryService partQueryService;

    public HomeCategoryPartsService(PartQueryService partQueryService) {
        this.partQueryService = partQueryService;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "'price-desc-v1'")
    public Map<String, Object> priceDescCategoryParts() {
        Map<String, Object> categoryParts = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            Map<String, Object> page = partQueryService.parts(
                    category, null, null, "ACTIVE", null, null, 0, 4, "price_desc"
            );
            categoryParts.put(category, page.getOrDefault("items", List.of()));
        }
        return categoryParts;
    }
}

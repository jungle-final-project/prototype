package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.part.catalog.PartQueryService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

@Service
public class HomeCategoryPartsService {
    public static final String CACHE_NAME = "home-category-parts";
    private static final int PARTS_PER_CATEGORY = 4;
    private static final List<String> CATEGORIES =
            List.of("CPU", "GPU", "MOTHERBOARD", "RAM", "STORAGE", "PSU", "CASE", "COOLER");

    private final PartQueryService partQueryService;
    private final Object buildLock = new Object();
    private CompletableFuture<Map<String, Object>> activeBuild;

    public HomeCategoryPartsService(PartQueryService partQueryService) {
        this.partQueryService = partQueryService;
    }

    @Cacheable(cacheNames = CACHE_NAME, key = "'price-desc-v1'")
    public Map<String, Object> priceDescCategoryParts() {
        CompletableFuture<Map<String, Object>> build;
        boolean owner = false;
        synchronized (buildLock) {
            if (activeBuild == null) {
                activeBuild = new CompletableFuture<>();
                owner = true;
            }
            build = activeBuild;
        }

        if (owner) {
            try {
                Map<String, Object> result = partQueryService.topActivePartsByCategoryPriceDesc(
                        CATEGORIES,
                        PARTS_PER_CATEGORY
                );
                build.complete(result);
                return result;
            } catch (RuntimeException | Error error) {
                build.completeExceptionally(error);
                throw error;
            } finally {
                synchronized (buildLock) {
                    if (activeBuild == build) {
                        activeBuild = null;
                    }
                }
            }
        }

        try {
            return build.join();
        } catch (CompletionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error fatal) {
                throw fatal;
            }
            throw new IllegalStateException(cause);
        }
    }
}

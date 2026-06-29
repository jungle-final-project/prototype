package com.buildgraph.prototype.part;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PartController {
    private final PartQueryService partQueryService;

    public PartController(PartQueryService partQueryService) {
        this.partQueryService = partQueryService;
    }

    @GetMapping("/parts")
    Map<String, Object> parts(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "manufacturer", required = false) String manufacturer,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "minPrice", required = false) Integer minPrice,
            @RequestParam(value = "maxPrice", required = false) Integer maxPrice,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestParam(value = "sort", required = false) String sort
    ) {
        return partQueryService.parts(category, query, manufacturer, status, minPrice, maxPrice, page, size, sort);
    }

    @GetMapping("/parts/{id}")
    Map<String, Object> part(@PathVariable String id) {
        return partQueryService.part(id);
    }

    @PostMapping("/tools/compatibility/check")
    Map<String, Object> compatibility(@RequestBody(required = false) Map<String, Object> request) {
        return tool("compatibility", request);
    }

    @PostMapping("/tools/power/check")
    Map<String, Object> power(@RequestBody(required = false) Map<String, Object> request) {
        return tool("power", request);
    }

    @PostMapping("/tools/size/check")
    Map<String, Object> size(@RequestBody(required = false) Map<String, Object> request) {
        return tool("size", request);
    }

    @PostMapping("/tools/performance/check")
    Map<String, Object> performance(@RequestBody(required = false) Map<String, Object> request) {
        return tool("performance", request);
    }

    @PostMapping("/tools/price/check")
    Map<String, Object> price(@RequestBody(required = false) Map<String, Object> request) {
        return tool("price", request);
    }

    private Map<String, Object> tool(String tool, Map<String, Object> request) {
        return partQueryService.toolResult(tool);
    }
}

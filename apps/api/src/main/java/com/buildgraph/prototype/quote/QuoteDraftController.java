package com.buildgraph.prototype.quote;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QuoteDraftController {
    private final QuoteDraftQueryService quoteDraftQueryService;

    public QuoteDraftController(QuoteDraftQueryService quoteDraftQueryService) {
        this.quoteDraftQueryService = quoteDraftQueryService;
    }

    @GetMapping("/quote-drafts/current")
    Map<String, Object> current(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return quoteDraftQueryService.current(authorization);
    }

    @PutMapping("/quote-drafts/current/items/{partId}")
    Map<String, Object> putItem(
            @PathVariable String partId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return quoteDraftQueryService.putItem(authorization, partId, request == null ? Map.of() : request);
    }

    @PatchMapping("/quote-drafts/current/items/{partId}")
    Map<String, Object> patchItem(
            @PathVariable String partId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return quoteDraftQueryService.patchItem(authorization, partId, request == null ? Map.of() : request);
    }

    @DeleteMapping("/quote-drafts/current/items/{partId}")
    Map<String, Object> deleteItem(
            @PathVariable String partId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return quoteDraftQueryService.deleteItem(authorization, partId);
    }
}

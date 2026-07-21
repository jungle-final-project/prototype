package com.buildgraph.prototype.quote;

import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class QuoteDraftController {
    private final QuoteDraftQueryService quoteDraftQueryService;
    private final QuoteDraftHistoryService quoteDraftHistoryService;

    public QuoteDraftController(
            QuoteDraftQueryService quoteDraftQueryService,
            QuoteDraftHistoryService quoteDraftHistoryService
    ) {
        this.quoteDraftQueryService = quoteDraftQueryService;
        this.quoteDraftHistoryService = quoteDraftHistoryService;
    }

    @GetMapping("/quote-drafts/current")
    Map<String, Object> current(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return quoteDraftQueryService.current(authorization);
    }

    @PutMapping("/quote-drafts/current/items/{partId}")
    Map<String, Object> putItem(
            @PathVariable String partId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Quote-Draft-Change-Group", required = false) String changeGroup
    ) {
        Map<String, Object> body = request == null ? Map.of() : request;
        return changeGroup == null
                ? quoteDraftQueryService.putItem(authorization, partId, body)
                : quoteDraftQueryService.putItem(authorization, partId, body, changeGroup);
    }

    @PutMapping("/quote-drafts/current/apply-ai-build")
    Map<String, Object> applyAiBuild(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Quote-Draft-Change-Group", required = false) String changeGroup
    ) {
        Map<String, Object> body = request == null ? Map.of() : request;
        return changeGroup == null
                ? quoteDraftQueryService.applyAiBuild(authorization, body)
                : quoteDraftQueryService.applyAiBuild(authorization, body, changeGroup);
    }

    @PatchMapping("/quote-drafts/current/items/{partId}")
    Map<String, Object> patchItem(
            @PathVariable String partId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Quote-Draft-Change-Group", required = false) String changeGroup
    ) {
        Map<String, Object> body = request == null ? Map.of() : request;
        return changeGroup == null
                ? quoteDraftQueryService.patchItem(authorization, partId, body)
                : quoteDraftQueryService.patchItem(authorization, partId, body, changeGroup);
    }

    @DeleteMapping("/quote-drafts/current/items/{partId}")
    Map<String, Object> deleteItem(
            @PathVariable String partId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Quote-Draft-Change-Group", required = false) String changeGroup
    ) {
        return changeGroup == null
                ? quoteDraftQueryService.deleteItem(authorization, partId)
                : quoteDraftQueryService.deleteItem(authorization, partId, changeGroup);
    }

    @GetMapping("/quote-drafts/current/history")
    Map<String, Object> history(
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return quoteDraftHistoryService.list(authorization);
    }

    @GetMapping("/quote-drafts/current/history/{historyId}/comparison")
    Map<String, Object> historyComparison(
            @PathVariable String historyId,
            @RequestParam(value = "game", required = false) String game,
            @RequestParam(value = "resolution", required = false) String resolution,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        return quoteDraftHistoryService.comparison(authorization, historyId, game, resolution);
    }

    @PostMapping("/quote-drafts/current/history/{historyId}/restore")
    Map<String, Object> restoreHistory(
            @PathVariable String historyId,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Quote-Draft-Change-Group", required = false) String changeGroup
    ) {
        return quoteDraftHistoryService.restore(
                authorization,
                historyId,
                request == null ? Map.of() : request,
                changeGroup
        );
    }
}

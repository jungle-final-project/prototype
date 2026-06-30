package com.buildgraph.prototype.rag;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class RagController {
    private final RagQueryService ragQueryService;
    private final CurrentUserService currentUserService;

    public RagController(RagQueryService ragQueryService, CurrentUserService currentUserService) {
        this.ragQueryService = ragQueryService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/rag/search")
    Map<String, Object> search(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "size", required = false) Integer size,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return ragQueryService.search(query, page, size);
    }

    @GetMapping("/rag/evidence/{id}")
    Map<String, Object> evidence(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return ragQueryService.evidence(id);
    }
}

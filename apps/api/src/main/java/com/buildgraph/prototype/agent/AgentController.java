package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgentController {
    private final AgentQueryService agentQueryService;
    private final CurrentUserService currentUserService;

    public AgentController(AgentQueryService agentQueryService, CurrentUserService currentUserService) {
        this.agentQueryService = agentQueryService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/agent/sessions")
    Map<String, Object> createSession(
            @RequestBody(required = false) AgentSessionCreateRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return agentQueryService.createSession(request);
    }

    @PostMapping("/agent/sessions/{id}/run")
    Map<String, Object> runSession(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return agentQueryService.runSession(id);
    }

    @GetMapping("/agent/sessions/{id}")
    Map<String, Object> getSession(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        currentUserService.requireUser(authorization);
        return agentQueryService.session(id);
    }
}

package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/support/chat-sessions")
public class SupportChatController {
    private final SupportChatService supportChatService;
    private final CurrentUserService currentUserService;

    public SupportChatController(SupportChatService supportChatService, CurrentUserService currentUserService) {
        this.supportChatService = supportChatService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/current")
    Map<String, Object> current(
            @RequestParam(value = "asTicketId", required = false) String asTicketId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return supportChatService.current(user, asTicketId);
    }

    @GetMapping("/{id}")
    Map<String, Object> detail(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return supportChatService.detail(id, user);
    }

    @PostMapping("/{id}/messages")
    Map<String, Object> postMessage(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return supportChatService.postUserMessage(id, request == null ? Map.of() : request, user);
    }
}

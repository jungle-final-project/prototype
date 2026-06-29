package com.buildgraph.prototype.agent;

import com.buildgraph.prototype.user.CurrentUserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AsChatController {
    private final AsChatService asChatService;
    private final CurrentUserService currentUserService;

    public AsChatController(AsChatService asChatService, CurrentUserService currentUserService) {
        this.asChatService = asChatService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/ai/as-chat")
    Map<String, Object> history(
            @RequestParam String asTicketId,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return asChatService.history(asTicketId, user);
    }

    @PostMapping("/ai/as-chat")
    Map<String, Object> send(
            @Valid @RequestBody AsChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return asChatService.send(request.asTicketId(), request.message(), user);
    }

    record AsChatRequest(@NotBlank String asTicketId, @NotBlank String message) {
    }
}

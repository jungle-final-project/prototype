package com.buildgraph.prototype.ticket;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TicketController {
    private final TicketQueryService ticketQueryService;
    private final CurrentUserService currentUserService;
    private final AdminSupportChatQueueWebSocketHandler adminSupportChatQueueWebSocketHandler;

    public TicketController(
            TicketQueryService ticketQueryService,
            CurrentUserService currentUserService,
            AdminSupportChatQueueWebSocketHandler adminSupportChatQueueWebSocketHandler
    ) {
        this.ticketQueryService = ticketQueryService;
        this.currentUserService = currentUserService;
        this.adminSupportChatQueueWebSocketHandler = adminSupportChatQueueWebSocketHandler;
    }

    @PostMapping("/as-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> create(
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        Map<String, Object> ticket = ticketQueryService.create(request, user);
        String supportChatRoomId = stringOrNull(ticket.get("supportChatRoomId"));
        if (supportChatRoomId != null) {
            adminSupportChatQueueWebSocketHandler.broadcastQueuePatch(supportChatRoomId);
        }
        return ticket;
    }

    @GetMapping("/as-tickets/{id}")
    Map<String, Object> ticket(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return ticketQueryService.ticket(id, user);
    }

    private static String stringOrNull(Object value) {
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isBlank() ? null : text;
    }
}

package com.buildgraph.prototype.admin;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/customer-contacts")
public class AdminCustomerContactController {
    private final CurrentUserService currentUserService;
    private final AdminCustomerContactService customerContactService;

    public AdminCustomerContactController(
            CurrentUserService currentUserService,
            AdminCustomerContactService customerContactService
    ) {
        this.currentUserService = currentUserService;
        this.customerContactService = customerContactService;
    }

    @GetMapping
    Map<String, Object> contacts(@RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return customerContactService.contacts();
    }

    @GetMapping("/{id}")
    Map<String, Object> contact(@PathVariable String id, @RequestHeader(value = "Authorization", required = false) String authorization) {
        currentUserService.requireAdmin(authorization);
        return customerContactService.contact(id);
    }

    @PostMapping("/{id}/messages")
    Map<String, Object> postMessage(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return customerContactService.postMessage(id, request, admin);
    }

    @PostMapping("/{id}/ticket")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createTicket(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> request,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return customerContactService.createTicket(id, request == null ? Map.of() : request, admin);
    }

    @PatchMapping("/{id}/archive")
    Map<String, Object> archive(
            @PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String authorization
    ) {
        CurrentUserService.CurrentUser admin = currentUserService.requireAdmin(authorization);
        return customerContactService.archive(id, admin);
    }
}

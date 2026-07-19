package com.buildgraph.prototype.recommendation;

import com.buildgraph.prototype.user.CurrentUserService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HomeController {
    private final AuthenticatedHomeService authenticatedHomeService;
    private final CurrentUserService currentUserService;

    public HomeController(
            AuthenticatedHomeService authenticatedHomeService,
            CurrentUserService currentUserService
    ) {
        this.authenticatedHomeService = authenticatedHomeService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/home")
    Map<String, Object> home(@RequestHeader(value = "Authorization", required = false) String authorization) {
        CurrentUserService.CurrentUser user = currentUserService.requireUser(authorization);
        return authenticatedHomeService.home(user);
    }
}

package com.buildgraph.prototype.log;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class AgentLogController {
    @PostMapping("/agent-logs/upload")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> upload(@RequestBody(required = false) Map<String, Object> request) {
        return LogSeed.upload();
    }

    @GetMapping("/agent-logs/{id}")
    Map<String, Object> log(@PathVariable String id) {
        return LogSeed.detail(id);
    }
}

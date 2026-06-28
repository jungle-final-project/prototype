package com.buildgraph.prototype.log;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class AgentLogController {
    @PostMapping("/agent-logs/upload")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> upload(
            @RequestParam(required = false) MultipartFile file,
            @RequestParam(required = false) Integer rangeMinutes,
            @RequestParam(required = false) Boolean consentAccepted
    ) {
        return LogSeed.upload();
    }

    @GetMapping("/agent-logs/{id}")
    Map<String, Object> log(@PathVariable String id) {
        return LogSeed.detail(id);
    }
}

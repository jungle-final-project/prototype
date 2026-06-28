package com.buildgraph.prototype.build;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class BuildController {
    @PostMapping("/requirements/parse")
    Map<String, Object> parse(@RequestBody Map<String, Object> request) {
        return BuildSeed.parsedRequirement(request);
    }

    @PostMapping("/builds/recommend")
    Map<String, Object> recommend(@RequestBody(required = false) Map<String, Object> request) {
        return BuildSeed.recommendations();
    }

    @GetMapping("/builds/{id}")
    Map<String, Object> build(@PathVariable String id) {
        return BuildSeed.buildDetail(id);
    }

    @GetMapping("/builds/history")
    Map<String, Object> history() {
        return Map.of("items", BuildSeed.builds());
    }

    @PostMapping("/builds/{id}/change-part")
    Map<String, Object> changePart(@PathVariable String id, @RequestBody(required = false) Map<String, Object> request) {
        return BuildSeed.changePart(id);
    }
}

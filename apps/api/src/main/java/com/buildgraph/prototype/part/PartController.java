package com.buildgraph.prototype.part;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PartController {
    @GetMapping("/parts")
    Map<String, Object> parts() {
        return Map.of("items", PartSeed.parts());
    }

    @GetMapping("/parts/{id}")
    Map<String, Object> part(@PathVariable String id) {
        return PartSeed.part(id);
    }

    @PostMapping("/tools/{tool}/check")
    Map<String, Object> tool(@PathVariable String tool, @RequestBody(required = false) Map<String, Object> request) {
        return ToolSeed.toolResult(tool);
    }
}

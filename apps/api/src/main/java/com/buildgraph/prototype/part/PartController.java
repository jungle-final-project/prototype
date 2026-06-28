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

    @PostMapping("/tools/compatibility/check")
    Map<String, Object> compatibility(@RequestBody(required = false) Map<String, Object> request) {
        return tool("compatibility", request);
    }

    @PostMapping("/tools/power/check")
    Map<String, Object> power(@RequestBody(required = false) Map<String, Object> request) {
        return tool("power", request);
    }

    @PostMapping("/tools/size/check")
    Map<String, Object> size(@RequestBody(required = false) Map<String, Object> request) {
        return tool("size", request);
    }

    @PostMapping("/tools/performance/check")
    Map<String, Object> performance(@RequestBody(required = false) Map<String, Object> request) {
        return tool("performance", request);
    }

    @PostMapping("/tools/price/check")
    Map<String, Object> price(@RequestBody(required = false) Map<String, Object> request) {
        return tool("price", request);
    }

    private Map<String, Object> tool(String tool, Map<String, Object> request) {
        return ToolSeed.toolResult(tool);
    }
}

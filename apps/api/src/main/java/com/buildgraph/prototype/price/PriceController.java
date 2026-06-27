package com.buildgraph.prototype.price;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class PriceController {
    @GetMapping("/price-alerts")
    Map<String, Object> alerts() {
        return PriceSeed.alerts();
    }

    @PostMapping("/price-alerts")
    @ResponseStatus(HttpStatus.CREATED)
    Map<String, Object> createAlert(@RequestBody(required = false) Map<String, Object> request) {
        return PriceSeed.createAlert();
    }

    @PostMapping("/price-snapshots/collect")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Map<String, Object> collectSnapshots() {
        return PriceSeed.collectSnapshots();
    }
}

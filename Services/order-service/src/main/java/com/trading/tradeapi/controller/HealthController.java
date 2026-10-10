package com.trading.tradeapi.controller;

import com.trading.tradeapi.portfolio.dto.HealthResponse;
import com.trading.tradeapi.portfolio.service.HealthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Port monitoring. Always answers 200 while the service runs; a degraded dependency is
 * reported in {@code status} and in its entry, so orchestration can act on a partial outage
 * without a TTL-less liveness probe flipping the instance.
 */
@RestController
public class HealthController {

    private final HealthService healthService;

    public HealthController(HealthService healthService) {
        this.healthService = healthService;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        return ResponseEntity.ok(healthService.checkHealth());
    }
}
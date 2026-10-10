package com.trading.tradeapi.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.Instant;
import java.util.List;

/**
 * The service and its dependencies, as the contract's {@code /health} specifies. It answers
 * {@code 200} while the service itself is running; a failing dependency degrades {@code status},
 * it does not fail the response.
 */
public record HealthResponse(
        String status,
        List<Dependency> dependencies,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant asOf) {

    public record Dependency(String name, String status, Integer quotaRemaining) {

        public static Dependency ok(String name) {
            return new Dependency(name, "ok", null);
        }

        public static Dependency withQuota(String name, Integer quotaRemaining) {
            return new Dependency(name, "ok", quotaRemaining);
        }

        public static Dependency degraded(String name) {
            return new Dependency(name, "degraded", null);
        }

        public static Dependency down(String name) {
            return new Dependency(name, "down", null);
        }
    }

    public boolean allUp() {
        return dependencies.stream().allMatch(d -> "ok".equals(d.status()));
    }
}
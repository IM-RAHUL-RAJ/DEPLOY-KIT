package com.trading.tradeapi.strategy.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record StrategyExecutionDto(
        long executionId,
        String signal,
        BigDecimal price,
        long quantity,
        String status,
        String orderId,
        String reason,
        Instant createdAt
) {}

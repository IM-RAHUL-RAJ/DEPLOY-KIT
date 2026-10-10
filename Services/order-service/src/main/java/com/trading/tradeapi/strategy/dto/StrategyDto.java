package com.trading.tradeapi.strategy.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record StrategyDto(
        long strategyId,
        long accountId,
        String name,
        String symbol,
        String strategyType,
        String side,
        BigDecimal targetPrice,
        String orderType,
        Integer shortWindow,
        Integer longWindow,
        Integer bandPeriod,
        BigDecimal bandK,
        long quantity,
        BigDecimal maxNotional,
        int maxOrdersPerDay,
        long maxPosition,
        String status,
        String stopReason,
        String lastState,
        BigDecimal lastPrice,
        Instant lastEvaluatedAt,
        int consecutiveFailures,
        Instant createdAt
) {}

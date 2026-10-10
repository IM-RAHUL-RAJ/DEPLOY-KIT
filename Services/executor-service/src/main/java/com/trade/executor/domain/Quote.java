package com.trade.executor.domain;

import java.math.BigDecimal;

public record Quote(
        String symbol,
        BigDecimal price,
        BigDecimal bid,
        BigDecimal ask,
        String currency,
        BigDecimal change,
        BigDecimal changePercent,
        BigDecimal previousClose,
        String marketState,
        boolean stale,
        String quoteAsOf
) {}

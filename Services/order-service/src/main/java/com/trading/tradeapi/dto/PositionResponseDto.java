package com.trading.tradeapi.dto;

import java.math.BigDecimal;

public record PositionResponseDto(
        Long accountId,
        String symbol,
        Long quantity,
        BigDecimal averageCost
) {}

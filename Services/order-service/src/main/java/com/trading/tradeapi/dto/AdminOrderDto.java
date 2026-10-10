package com.trading.tradeapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.Instant;

public record AdminOrderDto(
        String orderId,
        Long accountId,
        String holderName,
        String symbol,
        String side,
        Long quantity,
        BigDecimal price,
        String orderType,
        BigDecimal executedPrice,
        String status,
        String idempotencyKey,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdOn
) {}

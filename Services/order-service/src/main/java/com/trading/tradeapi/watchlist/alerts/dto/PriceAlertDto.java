package com.trading.tradeapi.watchlist.alerts.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * The read shape of a price alert: everything the inbox page needs, including the
 * status tip of the life cycle (ACTIVE, TRIGGERED, CANCELLED).
 */
public record PriceAlertDto(
        long alertId,
        long accountId,
        String symbol,
        String direction,
        BigDecimal threshold,
        String currency,
        String status,
        BigDecimal triggeredPrice,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant triggeredAt,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant createdAt) {
}
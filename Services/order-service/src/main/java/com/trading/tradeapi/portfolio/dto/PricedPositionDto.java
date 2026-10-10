package com.trading.tradeapi.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One open holding, price-derived figures in the instrument's own currency. {@code stale} is true
 * when the quote is past its freshness window or could not be fetched; the null-laden column in
 * the contract's example is that case, not an omission.
 */
public record PricedPositionDto(
        long accountId,
        String symbol,
        long quantity,
        BigDecimal averageCost,
        BigDecimal costBasis,
        BigDecimal lastPrice,
        BigDecimal marketValue,
        BigDecimal unrealisedPnl,
        BigDecimal unrealisedPnlPercent,
        String currency,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant priceAsOf,
        boolean stale) {

    public boolean priced() {
        return lastPrice != null;
    }
}
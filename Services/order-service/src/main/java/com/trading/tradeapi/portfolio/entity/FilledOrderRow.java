package com.trading.tradeapi.portfolio.entity;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A filled order row for the realised P&amp;L walk. {@code side} is the raw {@code BUY}/
 * {@code SELL} column value; both orders and the walk use the same casing.
 */
public record FilledOrderRow(
        String symbol,
        String side,
        BigDecimal quantity,
        BigDecimal executedPrice,
        Instant executedOn) {
}
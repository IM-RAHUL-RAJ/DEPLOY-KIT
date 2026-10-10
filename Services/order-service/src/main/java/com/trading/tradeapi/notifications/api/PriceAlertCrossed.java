package com.trading.tradeapi.notifications.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A price alert that has crossed its threshold, handed to the notifications module by the
 * watchlists module.
 *
 * <p>{@code eventId} is the unique id the alert consumption derived from the market-data event
 * that crossed the threshold. It is the idempotency key: if the same market-data message is ever
 * redelivered the threshold already being deactivated at the source is the backstop, and this id
 * makes the notification deduplication independent of either side's retry logic.
 */
public record PriceAlertCrossed(
        String eventId,
        long accountId,
        String symbol,
        String direction,
        BigDecimal threshold,
        BigDecimal lastPrice,
        Instant triggeredAt) {

    /** The one place the direction strings live; the alerts module stores the same names. */
    public static final String ABOVE = "ABOVE";
    public static final String BELOW = "BELOW";
}
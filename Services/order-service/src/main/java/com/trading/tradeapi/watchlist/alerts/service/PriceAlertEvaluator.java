package com.trading.tradeapi.watchlist.alerts.service;

import com.trading.tradeapi.notifications.api.PriceAlertCrossed;

import java.math.BigDecimal;

/**
 * The crossing rule, kept as a pure function so the arithmetic is unit-testable without a broker
 * or a database. See the binding contract for the table of directions.
 */
public final class PriceAlertEvaluator {

    private PriceAlertEvaluator() {
    }

    /**
     * Whether a quote crosses an alert's threshold.
     *
     * <p>{@code ABOVE} crosses when last price &gt; threshold, {@code BELOW} when last price
     * &lt; threshold. At exactly the threshold nothing fires, so a quote oscillating on the
     * threshold does not ratchet: the price has to move into the zone.
     */
    public static boolean crossed(String direction, BigDecimal threshold, BigDecimal price) {
        if (threshold == null || price == null || direction == null) {
            return false;
        }
        if (PriceAlertCrossed.ABOVE.equals(direction)) {
            return price.compareTo(threshold) > 0;
        }
        if (PriceAlertCrossed.BELOW.equals(direction)) {
            return price.compareTo(threshold) < 0;
        }
        return false;
    }
}
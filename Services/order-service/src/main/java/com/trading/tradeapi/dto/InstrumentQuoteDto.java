package com.trading.tradeapi.dto;

import java.math.BigDecimal;

/**
 * Live quote projection for the order ticket. {@code quoteCurrency} is the
 * instrument's native trading currency (e.g. INR for an Indian equity), which
 * lets the ticket show the native price beside the account-currency equivalent.
 */
public record InstrumentQuoteDto(
        String symbol,
        String quoteCurrency,
        BigDecimal price,
        BigDecimal bid,
        BigDecimal ask,
        boolean stale
) {
    /** The four-field form, for callers with no book depth to report. */
    public InstrumentQuoteDto(String symbol, String quoteCurrency, BigDecimal price, boolean stale) {
        this(symbol, quoteCurrency, price, null, null, stale);
    }

    /**
     * The price a market order on this side is expected to fill at: a buy lifts the
     * ask, a sell hits the bid, either falling back to the mid when the feed carries
     * only one side. Shown to the client as a preview; the server recomputes it.
     */
    public BigDecimal indicativePrice(boolean isBuy) {
        BigDecimal side = isBuy ? ask : bid;
        return side != null ? side : price;
    }
}

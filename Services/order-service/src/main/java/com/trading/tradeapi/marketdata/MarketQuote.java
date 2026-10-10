package com.trading.tradeapi.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The latest quote the market-data poller fetched for a symbol, as stored in
 * {@code trading.market_quote}. The order service never calls Fauxnance itself; it
 * reads this.
 *
 * @param quoteAsOf when Fauxnance observed the price; freshness is judged by this
 * @param fetchedAt when the poller fetched it
 */
public record MarketQuote(
        String symbol,
        BigDecimal price,
        BigDecimal bid,
        BigDecimal ask,
        String currency,
        BigDecimal change,
        BigDecimal changePercent,
        String marketState,
        boolean stale,
        Instant quoteAsOf,
        Instant fetchedAt
) {
    /** Side-aware native price: a buy lifts the ask, a sell hits the bid, else the last price. */
    public BigDecimal nativePriceFor(boolean isBuy) {
        BigDecimal side = isBuy ? ask : bid;
        return side != null ? side : price;
    }

    /** When the price was observed, falling back to when it was fetched. */
    public Instant observedAt() {
        return quoteAsOf != null ? quoteAsOf : fetchedAt;
    }
}

package com.trading.tradeapi.marketdata;

import java.math.BigDecimal;
import java.time.Instant;

/** Builders for market-data quotes in tests. */
public final class TestQuotes {

    private TestQuotes() {}

    /** A quote with only a last price. */
    public static MarketQuote q(String symbol, BigDecimal price, String currency, boolean stale) {
        return q(symbol, price, null, null, currency, stale);
    }

    /** A two-sided quote. */
    public static MarketQuote q(String symbol, BigDecimal price, BigDecimal bid, BigDecimal ask,
                                String currency, boolean stale) {
        Instant now = Instant.now();
        return new MarketQuote(symbol, price, bid, ask, currency, null, null, "open", stale, now, now);
    }

    /** The poller has this quote. */
    public static MarketDataService.QuoteLookup lookup(MarketQuote quote) {
        return new MarketDataService.QuoteLookup(quote, false);
    }

    /** Fauxnance does not know this symbol. */
    public static MarketDataService.QuoteLookup missing() {
        return new MarketDataService.QuoteLookup(null, true);
    }
}

package com.trade.executor.poller;

import com.trade.executor.domain.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * When a polled quote is worth a fresh, order-specific quote. Getting this wrong
 * either spends a request on an order that cannot fill, or leaves one unfilled.
 */
class MarketDataPollerRulesTest {

    private static Quote quote(String bid, String ask, boolean stale) {
        return new Quote("AAPL", new BigDecimal("100.00"),
                bid == null ? null : new BigDecimal(bid), ask == null ? null : new BigDecimal(ask),
                "USD", null, null, null, "open", stale, "2026-10-07T10:00:00Z");
    }

    @Test
    @DisplayName("a market order always warrants a fresh quote")
    void marketAlways() {
        assertThat(MarketDataPoller.mayExecute("MARKET", "BUY", null, quote("99", "101", true))).isTrue();
        assertThat(MarketDataPoller.mayExecute(null, "SELL", null, null)).isTrue();
    }

    @Test
    @DisplayName("a limit buy warrants one once the ask is at or below the limit")
    void limitBuy() {
        assertThat(MarketDataPoller.mayExecute("LIMIT", "BUY", new BigDecimal("101"), quote("99", "101", false))).isTrue();
        assertThat(MarketDataPoller.mayExecute("LIMIT", "BUY", new BigDecimal("100.99"), quote("99", "101", false))).isFalse();
    }

    @Test
    @DisplayName("a limit sell warrants one once the bid is at or above the limit")
    void limitSell() {
        assertThat(MarketDataPoller.mayExecute("LIMIT", "SELL", new BigDecimal("99"), quote("99", "101", false))).isTrue();
        assertThat(MarketDataPoller.mayExecute("LIMIT", "SELL", new BigDecimal("99.01"), quote("99", "101", false))).isFalse();
    }

    @Test
    @DisplayName("a limit order uses the last price when the quote carries no book side")
    void limitFallsBackToPrice() {
        assertThat(MarketDataPoller.mayExecute("LIMIT", "BUY", new BigDecimal("100"), quote(null, null, false))).isTrue();
        assertThat(MarketDataPoller.mayExecute("LIMIT", "SELL", new BigDecimal("100.01"), quote(null, null, false))).isFalse();
    }

    @Test
    @DisplayName("a stale quote never spends a request on a limit order, since it cannot fill one")
    void staleNeverForLimit() {
        assertThat(MarketDataPoller.mayExecute("LIMIT", "BUY", new BigDecimal("500"), quote("99", "101", true))).isFalse();
    }

    @Test
    @DisplayName("a limit order with no limit price or an unknown side is left alone")
    void malformed() {
        assertThat(MarketDataPoller.mayExecute("LIMIT", "BUY", null, quote("99", "101", false))).isFalse();
        assertThat(MarketDataPoller.mayExecute("LIMIT", "HOLD", new BigDecimal("100"), quote("99", "101", false))).isFalse();
    }
}

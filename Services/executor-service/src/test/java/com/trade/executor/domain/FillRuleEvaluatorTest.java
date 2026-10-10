package com.trade.executor.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pure unit tests for FillRuleEvaluator — no Spring context needed.
 * Grouped by what is being decided: buys, sells, rejections, and a missing or stale quote.
 */
@DisplayName("FillRuleEvaluator")
class FillRuleEvaluatorTest {

    private final FillRuleEvaluator evaluator = new FillRuleEvaluator();

    // A standard active quote: price=150, bid=149.50, ask=150.50
    private static final Quote ACTIVE_QUOTE = new Quote(
            "AAPL",
            new BigDecimal("150.00"),
            new BigDecimal("149.50"),
            new BigDecimal("150.50"),
            "USD",
            new BigDecimal("1.25"),
            new BigDecimal("0.84"),
            new BigDecimal("148.75"),
            "REGULAR",
            false,
            "2026-09-17T00:00:00Z"
    );

    private static final Quote STALE_QUOTE = new Quote(
            "AAPL",
            new BigDecimal("150.00"),
            new BigDecimal("149.50"),
            new BigDecimal("150.50"),
            "USD", null, null, null, "CLOSED",
            true, // stale=true
            "2026-09-16T00:00:00Z"
    );

    private static BigDecimal dec(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static Long qty(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    @Nested
    @DisplayName("Buy")
    class Buy {

        @Test
        @DisplayName("A limit at or above the ask fills at the ask")
        void fillsAtTheAsk() {
            FillDecision decision = evaluator.evaluate(
                    "BUY", 10L, dec("151.00"), ACTIVE_QUOTE,
                    "ACTIVE", 0L, BigDecimal.ZERO, dec("2000.00"), "LIMIT");

            assertThat(decision.filled()).isTrue();
            assertThat(decision.executedPrice()).isEqualByComparingTo("150.50");
            assertThat(decision.cashDelta()).isEqualByComparingTo("-1505.00"); // -150.50 * 10
            assertThat(decision.newHoldingQty()).isEqualTo(10L);
        }

        @Test
        @DisplayName("Adding to a holding averages the cost")
        void averagesAnExistingHolding() {
            // Already hold 10 at 140; buying 10 more at the 150.50 ask.
            FillDecision decision = evaluator.evaluate(
                    "BUY", 10L, dec("151.00"), ACTIVE_QUOTE,
                    "ACTIVE", 10L, dec("140.00"), dec("5000.00"), "LIMIT");

            assertThat(decision.filled()).isTrue();
            assertThat(decision.newHoldingQty()).isEqualTo(20L);
            // (10*140 + 10*150.50) / 20 = 145.25
            assertThat(decision.newAvgCost()).isEqualByComparingTo("145.25");
        }

        @Test
        @DisplayName("Costing exactly the cash balance fills: the check is strictly less-than")
        void cashExactlyCoveringTheCostFills() {
            FillDecision decision = evaluator.evaluate(
                    "BUY", 10L, null, ACTIVE_QUOTE,
                    "ACTIVE", 0L, BigDecimal.ZERO, dec("1505.00"), "MARKET");

            assertThat(decision.filled()).isTrue();
            assertThat(decision.cashDelta()).isEqualByComparingTo("-1505.00");
        }
    }

    @Nested
    @DisplayName("Sell")
    class Sell {

        @Test
        @DisplayName("A limit at or below the bid fills at the bid")
        void fillsAtTheBid() {
            FillDecision decision = evaluator.evaluate(
                    "SELL", 5L, dec("149.00"), ACTIVE_QUOTE,
                    "ACTIVE", 10L, dec("140.00"), dec("0.00"), "LIMIT");

            assertThat(decision.filled()).isTrue();
            assertThat(decision.executedPrice()).isEqualByComparingTo("149.50");
            assertThat(decision.cashDelta()).isEqualByComparingTo("747.50"); // +149.50 * 5
            assertThat(decision.newHoldingQty()).isEqualTo(5L);
        }

        @Test
        @DisplayName("Selling the whole holding leaves zero")
        void sellingEverythingLeavesZero() {
            FillDecision decision = evaluator.evaluate(
                    "SELL", 10L, dec("149.00"), ACTIVE_QUOTE,
                    "ACTIVE", 10L, dec("140.00"), BigDecimal.ZERO, "LIMIT");

            assertThat(decision.filled()).isTrue();
            assertThat(decision.newHoldingQty()).isEqualTo(0L);
        }

        @Test
        @DisplayName("Proceeds are bid × quantity and the remaining cost basis is unchanged")
        void proceedsAtTheBidKeepTheCostBasis() {
            FillDecision decision = evaluator.evaluate(
                    "SELL", 4L, null, ACTIVE_QUOTE,
                    "ACTIVE", 10L, dec("120.00"), BigDecimal.ZERO, "MARKET");

            assertThat(decision.filled()).isTrue();
            assertThat(decision.executedPrice()).isEqualByComparingTo("149.50");
            assertThat(decision.cashDelta()).isEqualByComparingTo("598.00");
            assertThat(decision.newHoldingQty()).isEqualTo(6L);
            assertThat(decision.newAvgCost()).isEqualByComparingTo("120.00");
        }
    }

    @Nested
    @DisplayName("Rejections (funds and holdings are decided here, never at admission)")
    class Rejections {

        // PRICE_NOT_MET is a "not yet" (the poller retries); the others are final.
        @ParameterizedTest(name = "{0} {1} @ {2} ({8}), account {3} → {7}")
        @CsvSource({
                // side, qty, limit,  account,     held, avgCost, cash,     reason,                orderType
                "BUY,  10,  149.00, ACTIVE,      0,    0,       5000.00,  PRICE_NOT_MET,         LIMIT",
                "BUY,  100, 151.00, ACTIVE,      0,    0,       100.00,   INSUFFICIENT_FUNDS,    LIMIT",
                "BUY,  10,  ,       ACTIVE,      0,    0,       1000.00,  INSUFFICIENT_FUNDS,    MARKET",
                "SELL, 5,   152.00, ACTIVE,      10,   140.00,  0,        PRICE_NOT_MET,         LIMIT",
                "SELL, 20,  149.00, ACTIVE,      5,    140.00,  0,        INSUFFICIENT_HOLDINGS, LIMIT",
                "SELL, 1,   ,       ACTIVE,      ,     ,        5000.00,  INSUFFICIENT_HOLDINGS, MARKET",
                "BUY,  10,  151.00, SUSPENDED,   0,    0,       5000.00,  ACCOUNT_NOT_ACTIVE,    LIMIT",
                "HOLD, 10,  151.00, ACTIVE,      0,    0,       5000.00,  INVALID_SIDE,          LIMIT",
        })
        void rejects(String side, long quantity, String limit, String account, String held, String avgCost,
                     String cash, String reason, String orderType) {
            FillDecision decision = evaluator.evaluate(
                    side, quantity, dec(limit), ACTIVE_QUOTE, account, qty(held), dec(avgCost), dec(cash), orderType);

            assertThat(decision.filled()).isFalse();
            assertThat(decision.rejectionReason()).isEqualTo(reason);
        }
    }

    @Nested
    @DisplayName("No usable quote")
    class NoUsableQuote {

        @Test
        @DisplayName("A market order with no quote is rejected: there is no price to fill against")
        void marketWithoutAQuoteIsRejected() {
            FillDecision decision = evaluator.evaluate(
                    "BUY", 10L, null, null,
                    "ACTIVE", 0L, BigDecimal.ZERO, dec("5000.00"), "MARKET");

            assertThat(decision.filled()).isFalse();
            assertThat(decision.rejectionReason()).isEqualTo("PRICE_NOT_AVAILABLE");
        }

        @ParameterizedTest(name = "quote {0} → pending with {1}")
        @CsvSource({"missing, QUOTE_UNAVAILABLE", "stale, PRICE_STALE"})
        @DisplayName("A limit order waits as pending for the poller, rather than being thrown away")
        void limitWaitsForThePoller(String quote, String reason) {
            // A limit order carries its own price, so a missing or stale quote is temporary.
            FillDecision decision = evaluator.evaluate(
                    "BUY", 10L, dec("151.00"), quote.equals("stale") ? STALE_QUOTE : null,
                    "ACTIVE", 0L, BigDecimal.ZERO, dec("5000.00"), "LIMIT");

            assertThat(decision.filled()).isFalse();
            assertThat(decision.pending()).isTrue();
            assertThat(decision.rejectionReason()).contains(reason);
        }
    }
}

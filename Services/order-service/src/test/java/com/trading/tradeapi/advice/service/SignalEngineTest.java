package com.trading.tradeapi.advice.service;

import com.trading.tradeapi.advice.dto.Methodology;
import com.trading.tradeapi.advice.dto.TradeSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Trade advice signal engine")
public class SignalEngineTest {

    /** Alternating moves: net drift per two days is up - down, RSI sits near 100*up/(up+down). */
    public static List<Double> zigzag(int n, double up, double down) {
        List<Double> closes = new ArrayList<>();
        double price = 100;
        for (int i = 0; i < n; i++) {
            price += (i % 2 == 0) ? up : -down;
            closes.add(price);
        }
        return closes;
    }

    private static TradeSignal.Factor factor(TradeSignal s, String key) {
        return s.factors().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("An uptrend with neutral momentum is a BUY, with all five factors scored and explained")
    void buySignalWithReasoning() {
        TradeSignal s = SignalEngine.evaluate("AAPL", zigzag(61, 2, 1), null, null, null, List.of("WATCHLIST"));

        assertThat(s.status()).isEqualTo(TradeSignal.SIGNAL);
        assertThat(s.action()).isEqualTo("BUY");
        assertThat(s.score()).isGreaterThanOrEqualTo(2);
        assertThat(s.strength()).isEqualTo(Math.round(s.score() * 100f / 5));
        assertThat(s.factors()).extracting(TradeSignal.Factor::key)
                .containsExactly("TREND", "POSITION", "RSI", "MACD", "BOLLINGER");
        assertThat(factor(s, "TREND").score()).isEqualTo(1);
        assertThat(factor(s, "TREND").reason()).startsWith("Uptrend");
        assertThat(factor(s, "POSITION").score()).isEqualTo(1);
        assertThat(factor(s, "RSI").score()).isZero();
        assertThat(s.reasons()).anyMatch(r -> r.startsWith("Context: 52-week range"));
        assertThat(s.indicators().sma20()).isGreaterThan(s.indicators().sma50());
        assertThat(s.indicators().macd()).isNotNull();
        assertThat(s.indicators().percentB()).isNotNull();
        assertThat(s.indicators().sma200()).isNull();
        assertThat(s.summary()).contains("BUY");
    }

    @Test
    @DisplayName("A downtrend is a SELL, and a holding is weighed into the reasoning")
    void sellSignalAgainstHolding() {
        TradeSignal.Holding holding = new TradeSignal.Holding(10, 120.0, -12.5);
        TradeSignal s = SignalEngine.evaluate("MSFT", zigzag(60, 1, 2), null, Instant.now(), holding,
                List.of("HOLDING"));

        assertThat(s.action()).isEqualTo("SELL");
        assertThat(s.score()).isLessThanOrEqualTo(-2);
        assertThat(factor(s, "TREND").reason()).startsWith("Downtrend");
        assertThat(s.reasons()).anyMatch(r -> r.contains("You hold 10") && r.contains("-12.50%"));
        assertThat(s.holding()).isEqualTo(holding);
    }

    @Test
    @DisplayName("With a year of history the trend compares the 50-day and 200-day averages")
    void longTrendUsesTwoHundredDay() {
        TradeSignal s = SignalEngine.evaluate("AAPL", zigzag(220, 2, 1), null, null, null, List.of("WATCHLIST"));

        assertThat(s.indicators().sma200()).isNotNull();
        assertThat(factor(s, "TREND").reason()).contains("200-day average");
        assertThat(s.indicators().high52w()).isGreaterThan(s.indicators().low52w());
        assertThat(s.indicators().volatility()).isPositive();
    }

    @Test
    @DisplayName("A sharp run-up reads overbought on RSI")
    void overbought() {
        List<Double> closes = new ArrayList<>(java.util.Collections.nCopies(50, 100.0));
        for (int i = 1; i <= 15; i++) {
            closes.add(100.0 + 3 * i);
        }
        TradeSignal s = SignalEngine.evaluate("NVDA", closes, null, null, null, List.of("WATCHLIST"));

        assertThat(factor(s, "RSI").score()).isEqualTo(-1);
        assertThat(factor(s, "RSI").reason()).startsWith("Overbought");
        assertThat(factor(s, "MACD").score()).isEqualTo(1);
    }

    @Test
    @DisplayName("A live price under the lower band reads stretched down and below the average")
    void livePriceIsUsed() {
        TradeSignal s = SignalEngine.evaluate("AAPL", zigzag(60, 2, 1), 1.0, Instant.now(), null, List.of("REQUESTED"));

        assertThat(s.indicators().lastPrice()).isEqualTo(1.0);
        assertThat(factor(s, "POSITION").score()).isEqualTo(-1);
        assertThat(factor(s, "BOLLINGER").score()).isEqualTo(1);
        assertThat(s.indicators().percentB()).isNegative();
    }

    @Test
    @DisplayName("Fewer than 50 daily closes is no signal at all, and says why")
    void noSignalWhenDataIsInsufficient() {
        TradeSignal s = SignalEngine.evaluate("NEWCO", zigzag(30, 2, 1), 130.0, Instant.now(), null,
                List.of("WATCHLIST"));

        assertThat(s.status()).isEqualTo(TradeSignal.INSUFFICIENT_DATA);
        assertThat(s.action()).isNull();
        assertThat(s.strength()).isZero();
        assertThat(s.factors()).isEmpty();
        assertThat(s.reasons()).singleElement().asString().contains("50").contains("30 available");
    }

    @Test
    @DisplayName("No history at all is no signal either")
    void noSignalWithoutHistory() {
        TradeSignal s = SignalEngine.evaluate("NEWCO", null, null, null, null, List.of("WATCHLIST"));

        assertThat(s.status()).isEqualTo(TradeSignal.INSUFFICIENT_DATA);
        assertThat(s.indicators().dailyCloses()).isZero();
    }

    @Test
    @DisplayName("The methodology states a formula and a rule for every factor")
    void methodologyIsComplete() {
        Methodology m = SignalEngine.methodology("Not advice.");

        assertThat(m.factors()).extracting(Methodology.Formula::key)
                .containsExactly("TREND", "POSITION", "RSI", "MACD", "BOLLINGER", "CONTEXT");
        assertThat(m.factors()).allMatch(f -> !f.formula().isBlank() && !f.rule().isBlank());
        assertThat(m.disclaimer()).isEqualTo("Not advice.");
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("Extreme and flat markets")
    class Extremes {

        private List<Double> line(int n, double start, double step) {
            return java.util.stream.IntStream.range(0, n).mapToObj(i -> start + i * step).toList();
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("A long slide reads as a downtrend and oversold, and a SELL view for a non-holder explains itself")
        void slide() {
            TradeSignal s = SignalEngine.evaluate("DOWN", line(220, 400, -1.5), null, null, null, List.of("WATCHLIST"));
            assertThat(s.reasons()).isNotEmpty();
            assertThat(factor(s, "TREND").score()).isEqualTo(-1);
            assertThat(factor(s, "RSI").score()).isEqualTo(1);
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("A spike above the upper band is stretched to the upside")
        void spike() {
            List<Double> closes = new java.util.ArrayList<>(line(60, 100, 0.01));
            TradeSignal s = SignalEngine.evaluate("UP", closes, 200.0, null, null, List.of("REQUESTED"));
            assertThat(factor(s, "BOLLINGER").score()).isEqualTo(-1);
        }

        @org.junit.jupiter.api.Test
        @org.junit.jupiter.api.DisplayName("A perfectly flat price has flat bands and a MACD on its signal line")
        void flat() {
            TradeSignal s = SignalEngine.evaluate("FLAT", line(60, 100, 0), null, null, null, List.of("REQUESTED"));
            assertThat(factor(s, "BOLLINGER").reason()).contains("flat");
            assertThat(factor(s, "MACD").score()).isZero();
        }
    }
}

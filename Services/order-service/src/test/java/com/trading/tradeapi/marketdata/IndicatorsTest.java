package com.trading.tradeapi.marketdata;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("Technical indicators")
class IndicatorsTest {

    private static List<Double> oneToTen() {
        List<Double> s = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            s.add((double) i);
        }
        return s;
    }

    @Test
    @DisplayName("SMA averages the last n values; too few is empty")
    void sma() {
        assertThat(Indicators.sma(oneToTen(), 4).getAsDouble()).isEqualTo(8.5);
        assertThat(Indicators.sma(oneToTen(), 11)).isEmpty();
    }

    @Test
    @DisplayName("EMA is seeded with the SMA and smoothed with 2 / (n + 1)")
    void ema() {
        List<Double> ema = Indicators.emaSeries(oneToTen(), 3);
        assertThat(ema.get(1)).isNull();
        assertThat(ema.get(2)).isEqualTo(2.0);          // SMA of 1, 2, 3
        assertThat(ema.get(3)).isEqualTo(3.0);          // 0.5 * 4 + 0.5 * 2
        assertThat(ema.get(9)).isCloseTo(9.0, within(1e-9));
    }

    @Test
    @DisplayName("MACD of a flat series is zero; of a rising one, positive")
    void macd() {
        Indicators.Macd flat = Indicators.macd(Collections.nCopies(40, 50.0), 12, 26, 9).orElseThrow();
        assertThat(flat.line()).isZero();
        assertThat(flat.histogram()).isZero();

        List<Double> rising = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            rising.add(100.0 + i);
        }
        assertThat(Indicators.macd(rising, 12, 26, 9).orElseThrow().line()).isPositive();
        assertThat(Indicators.macd(rising.subList(0, 33), 12, 26, 9)).isEmpty();
    }

    @Test
    @DisplayName("RSI is 100 with no losses and 50 when gains equal losses")
    void rsi() {
        assertThat(Indicators.rsi(oneToTen(), 5).getAsDouble()).isEqualTo(100);
        List<Double> even = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            even.add(i % 2 == 0 ? 100.0 : 101.0);
        }
        assertThat(Indicators.rsi(even, 14).getAsDouble()).isCloseTo(50, within(5.0));
    }

    @Test
    @DisplayName("Bollinger bands are the SMA plus and minus k population standard deviations")
    void bollinger() {
        Indicators.Bands b = Indicators.bollinger(List.of(2.0, 4.0, 4.0, 4.0, 5.0, 5.0, 7.0, 9.0), 8, 2).orElseThrow();
        assertThat(b.middle()).isEqualTo(5.0);
        assertThat(b.upper()).isEqualTo(9.0);  // sd 2
        assertThat(b.lower()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Volatility is zero for a flat series and annualised by sqrt(252)")
    void volatility() {
        assertThat(Indicators.annualisedVolatility(Collections.nCopies(30, 10.0), 252).getAsDouble()).isZero();
        List<Double> alternating = new ArrayList<>();
        for (int i = 0; i < 101; i++) {
            alternating.add(i % 2 == 0 ? 100.0 : 101.0);
        }
        double v = Indicators.annualisedVolatility(alternating, 252).getAsDouble();
        // daily log return magnitude ~0.995%, sd ~1%, times sqrt(252) ~ 15.8%
        assertThat(v).isCloseTo(15.8, within(0.5));
        assertThat(Indicators.annualisedVolatility(List.of(1.0, 2.0), 252)).isEmpty();
    }
}

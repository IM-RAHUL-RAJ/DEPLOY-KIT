package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.strategy.entity.StrategyRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Strategy rules")
class StrategyRulesTest {

    static StrategyRecord crossover(int shortWindow, int longWindow) {
        StrategyRecord s = new StrategyRecord();
        s.setStrategyType(StrategyRules.MA_CROSSOVER);
        s.setShortWindow(shortWindow);
        s.setLongWindow(longWindow);
        return s;
    }

    static StrategyRecord bands(int period, double k) {
        StrategyRecord s = new StrategyRecord();
        s.setStrategyType(StrategyRules.BOLLINGER);
        s.setBandPeriod(period);
        s.setBandK(BigDecimal.valueOf(k));
        return s;
    }

    static StrategyRecord target(String side, double price) {
        StrategyRecord s = new StrategyRecord();
        s.setStrategyType(StrategyRules.PRICE_TARGET);
        s.setSide(side);
        s.setTargetPrice(BigDecimal.valueOf(price));
        return s;
    }

    @Test
    @DisplayName("A buy target is hit at or below the target, a sell target at or above, with no history needed")
    void priceTarget() {
        StrategyRecord buy = target("BUY", 180);
        assertThat(StrategyRules.read(buy, null, 185).orElseThrow().state()).isEqualTo(StrategyRules.WAITING);
        assertThat(StrategyRules.read(buy, null, 180).orElseThrow().state()).isEqualTo(StrategyRules.HIT);
        assertThat(StrategyRules.read(buy, List.of(), 170).orElseThrow().state()).isEqualTo(StrategyRules.HIT);

        StrategyRecord sell = target("SELL", 200);
        assertThat(StrategyRules.read(sell, null, 199).orElseThrow().state()).isEqualTo(StrategyRules.WAITING);
        assertThat(StrategyRules.read(sell, null, 201).orElseThrow().state()).isEqualTo(StrategyRules.HIT);

        assertThat(StrategyRules.closesNeeded(buy)).isZero();
    }

    @Test
    @DisplayName("A price target fires on the first hit, with no previous state needed; waiting fires nothing")
    void priceTargetTrigger() {
        StrategyRecord buy = target("BUY", 180);
        assertThat(StrategyRules.triggerFor(buy, null, StrategyRules.HIT)).contains("BUY");
        assertThat(StrategyRules.triggerFor(buy, StrategyRules.WAITING, StrategyRules.WAITING)).isEmpty();
        assertThat(StrategyRules.triggerFor(target("SELL", 200), StrategyRules.WAITING, StrategyRules.HIT)).contains("SELL");
        // The indicator rules still need a change of state.
        assertThat(StrategyRules.triggerFor(crossover(3, 5), null, StrategyRules.ABOVE)).isEmpty();
        StrategyRecord noTarget = target("BUY", 1);
        noTarget.setTargetPrice(null);
        assertThat(StrategyRules.read(noTarget, null, 1)).isEmpty();
    }

    /** Falling closes: the short average sits below the long one. */
    static final List<Double> FALLING = List.of(110.0, 108.0, 106.0, 104.0, 102.0, 100.0);

    @Test
    @DisplayName("Crossover reads BELOW on a falling series and ABOVE once a jump lifts the short average")
    void crossoverState() {
        StrategyRecord s = crossover(3, 5);

        StrategyRules.Reading low = StrategyRules.read(s, FALLING, 99).orElseThrow();
        assertThat(low.state()).isEqualTo(StrategyRules.BELOW);
        assertThat(low.primary()).isLessThan(low.secondary());

        StrategyRules.Reading high = StrategyRules.read(s, FALLING, 130).orElseThrow();
        assertThat(high.state()).isEqualTo(StrategyRules.ABOVE);
    }

    @Test
    @DisplayName("A crossover triggers on the change of state: up is BUY, down is SELL")
    void crossoverTriggers() {
        assertThat(StrategyRules.trigger(StrategyRules.MA_CROSSOVER, "BELOW", "ABOVE")).contains("BUY");
        assertThat(StrategyRules.trigger(StrategyRules.MA_CROSSOVER, "ABOVE", "BELOW")).contains("SELL");
    }

    @Test
    @DisplayName("No trigger while the state holds, nor on the first reading")
    void noTriggerWithoutAChange() {
        assertThat(StrategyRules.trigger(StrategyRules.MA_CROSSOVER, "ABOVE", "ABOVE")).isEmpty();
        assertThat(StrategyRules.trigger(StrategyRules.MA_CROSSOVER, "BELOW", "BELOW")).isEmpty();
        assertThat(StrategyRules.trigger(StrategyRules.MA_CROSSOVER, null, "ABOVE")).isEmpty();
    }

    @Test
    @DisplayName("Too little history reads nothing")
    void insufficientHistory() {
        assertThat(StrategyRules.read(crossover(3, 5), List.of(100.0, 101.0), 102)).isEmpty();
        assertThat(StrategyRules.read(crossover(3, 5), null, 102)).isEmpty();
        assertThat(StrategyRules.closesNeeded(crossover(3, 5))).isEqualTo(4);
    }

    @Test
    @DisplayName("Bollinger: a break below the lower band is BUY, above the upper is SELL, inside is nothing")
    void bollinger() {
        List<Double> flat = List.of(100.0, 101.0, 99.0, 100.0, 101.0, 99.0, 100.0, 101.0, 99.0);
        StrategyRecord s = bands(10, 2);

        assertThat(StrategyRules.read(s, flat, 100).orElseThrow().state()).isEqualTo(StrategyRules.INSIDE);
        StrategyRules.Reading crash = StrategyRules.read(s, flat, 80).orElseThrow();
        assertThat(crash.state()).isEqualTo(StrategyRules.BELOW);
        assertThat(crash.lower()).isLessThan(crash.upper());
        assertThat(StrategyRules.read(s, flat, 120).orElseThrow().state()).isEqualTo(StrategyRules.ABOVE);

        assertThat(StrategyRules.trigger(StrategyRules.BOLLINGER, "INSIDE", "BELOW")).contains("BUY");
        assertThat(StrategyRules.trigger(StrategyRules.BOLLINGER, "INSIDE", "ABOVE")).contains("SELL");
        assertThat(StrategyRules.trigger(StrategyRules.BOLLINGER, "BELOW", "INSIDE")).isEmpty();
        assertThat(StrategyRules.trigger(StrategyRules.BOLLINGER, "BELOW", "BELOW")).isEmpty();
    }
}

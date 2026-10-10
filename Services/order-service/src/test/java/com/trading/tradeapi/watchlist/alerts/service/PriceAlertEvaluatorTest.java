package com.trading.tradeapi.watchlist.alerts.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PriceAlertEvaluatorTest {

    @Test
    @DisplayName("ABOVE crosses strictly above the threshold")
    void aboveCrossesAbove() {
        assertThat(PriceAlertEvaluator.crossed("ABOVE", new BigDecimal("100.00"), new BigDecimal("100.01"))).isTrue();
    }

    @Test
    @DisplayName("ABOVE does not cross at or below the threshold")
    void aboveDoesNotCrossAtOrBelow() {
        assertThat(PriceAlertEvaluator.crossed("ABOVE", new BigDecimal("100.00"), new BigDecimal("100.00"))).isFalse();
        assertThat(PriceAlertEvaluator.crossed("ABOVE", new BigDecimal("100.00"), new BigDecimal("99.99"))).isFalse();
    }

    @Test
    @DisplayName("BELOW crosses strictly below the threshold")
    void belowCrossesBelow() {
        assertThat(PriceAlertEvaluator.crossed("BELOW", new BigDecimal("50.00"), new BigDecimal("49.99"))).isTrue();
    }

    @Test
    @DisplayName("BELOW does not cross at or above the threshold")
    void belowDoesNotCrossAtOrAbove() {
        assertThat(PriceAlertEvaluator.crossed("BELOW", new BigDecimal("50.00"), new BigDecimal("50.00"))).isFalse();
        assertThat(PriceAlertEvaluator.crossed("BELOW", new BigDecimal("50.00"), new BigDecimal("50.01"))).isFalse();
    }

    @Test
    @DisplayName("an unknown direction never crosses")
    void unknownDirectionNeverCrosses() {
        assertThat(PriceAlertEvaluator.crossed("SIDEWAYS", new BigDecimal("100.00"), new BigDecimal("999.00"))).isFalse();
    }

    @Test
    @DisplayName("null threshold, price or direction never crosses")
    void nullsNeverCross() {
        assertThat(PriceAlertEvaluator.crossed(null, new BigDecimal("100.00"), new BigDecimal("999.00"))).isFalse();
        assertThat(PriceAlertEvaluator.crossed("ABOVE", null, new BigDecimal("999.00"))).isFalse();
        assertThat(PriceAlertEvaluator.crossed("ABOVE", new BigDecimal("100.00"), null)).isFalse();
    }
}
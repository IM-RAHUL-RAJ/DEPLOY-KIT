package com.trade.executor.service;

import com.trade.executor.domain.Quote;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Pure unit tests for CurrencyConverter — no Spring context needed.
 * Verifies that a native (e.g. INR) quote is re-denominated into the USD
 * ledger before a fill is evaluated or settled.
 */
class CurrencyConverterTest {

    private CurrencyConverter converter;

    @BeforeEach
    void setUp() {
        converter = new CurrencyConverter();
        converter.setBase("USD");
        converter.setRates(Map.of(
                "USD", BigDecimal.ONE,
                "INR", new BigDecimal("83.3")));
    }

    @Test
    @DisplayName("convert: INR is divided by the INR-per-USD reference rate")
    void convertsInrToUsd() {
        assertThat(converter.convert(new BigDecimal("1035"), "INR", "USD"))
                .isCloseTo(new BigDecimal("12.42496999"), within(new BigDecimal("0.000001")));
    }

    @Test
    @DisplayName("convert: identical currencies are a no-op")
    void identityWhenSameCurrency() {
        assertThat(converter.convert(new BigDecimal("10.00"), "USD", "USD"))
                .isEqualByComparingTo("10.00");
    }

    @Test
    @DisplayName("convert: a null amount stays null")
    void nullAmountStaysNull() {
        assertThat(converter.convert(null, "INR", "USD")).isNull();
    }

    @Test
    @DisplayName("convert: an unknown currency falls back to a 1:1 rate")
    void unknownCurrencyFallsBack() {
        assertThat(converter.convert(new BigDecimal("50"), "USD", "XYZ"))
                .isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("toAccountCurrency: re-denominates price, bid and ask into USD")
    void reDenominatesQuote() {
        Quote nativeQuote = new Quote(
                "INFY.NS",
                new BigDecimal("1035.00"),
                new BigDecimal("1034.77"),
                new BigDecimal("1035.23"),
                "INR",
                new BigDecimal("5.00"),
                new BigDecimal("0.50"),
                new BigDecimal("1030.00"),
                "REGULAR",
                false,
                "2026-01-01T00:00:00Z");

        Quote usd = converter.toAccountCurrency(nativeQuote, "USD");

        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(usd.price()).isCloseTo(new BigDecimal("12.42497"), within(new BigDecimal("0.0001")));
        assertThat(usd.bid()).isCloseTo(new BigDecimal("12.42221"), within(new BigDecimal("0.0001")));
        assertThat(usd.ask()).isCloseTo(new BigDecimal("12.42773"), within(new BigDecimal("0.0001")));
        // changePercent is a ratio, not money — it is left untouched.
        assertThat(usd.changePercent()).isEqualByComparingTo("0.50");
    }

    @Test
    @DisplayName("toAccountCurrency: a USD quote against a USD account is returned unchanged")
    void usdQuoteUnchanged() {
        Quote usdQuote = new Quote(
                "AAPL", new BigDecimal("333.69"), null, null, "USD",
                null, null, null, null, false, null);

        assertThat(converter.toAccountCurrency(usdQuote, "USD")).isSameAs(usdQuote);
    }
}

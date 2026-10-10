package com.trading.tradeapi.service;

import com.trading.tradeapi.exception.UnsupportedCurrencyException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class CurrencyConverterUnitTest {

    private CurrencyConverter converter;

    @BeforeEach
    public void setup() {
        converter = new CurrencyConverter();
        converter.setBase("USD");
        converter.setRates(Map.of(
                "USD", new BigDecimal("1"),
                "EUR", new BigDecimal("0.92"),
                "INR", new BigDecimal("83.3")));
    }

    @Test
    @DisplayName("Converting between the same currency is an identity")
    public void sameCurrencyIsIdentity() {
        assertEquals(new BigDecimal("150.00"), converter.convert(new BigDecimal("150.00"), "USD", "USD"));
    }

    @Test
    @DisplayName("USD to INR multiplies by the per-USD rate")
    public void usdToInr() {
        assertEquals(0, new BigDecimal("833.00000000").compareTo(converter.convert(new BigDecimal("10"), "USD", "INR")));
    }

    @Test
    @DisplayName("INR to USD divides by the per-USD rate")
    public void inrToUsd() {
        assertEquals(0, new BigDecimal("12.42496999")
                .compareTo(converter.convert(new BigDecimal("1035"), "INR", "USD")));
    }

    @Test
    @DisplayName("A null amount converts to null")
    public void nullAmount() {
        assertNull(converter.convert(null, "USD", "INR"));
    }

    @Test
    @DisplayName("An unknown currency is refused, not priced at 1.0")
    public void unknownCurrencyRefused() {
        // Defaulting a missing rate to 1.0 would price the order, and check its
        // affordability, on a conversion that was never performed.
        UnsupportedCurrencyException thrown = assertThrows(UnsupportedCurrencyException.class,
                () -> converter.convert(new BigDecimal("10"), "XYZ", "USD"));
        assertEquals("FX-422", thrown.getCode());
        assertTrue(thrown.getMessage().contains("XYZ"));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, thrown.getHttpStatus());
    }

    @Test
    @DisplayName("An unknown target currency is refused too")
    public void unknownTargetRefused() {
        assertThrows(UnsupportedCurrencyException.class,
                () -> converter.convert(new BigDecimal("10"), "USD", "ZZZ"));
    }

    @Test
    @DisplayName("A rate configured as zero is refused rather than dividing by zero")
    public void zeroRateRefused() {
        converter.setRates(Map.of("USD", new BigDecimal("1"), "EUR", BigDecimal.ZERO));

        assertThrows(UnsupportedCurrencyException.class,
                () -> converter.convert(new BigDecimal("10"), "USD", "EUR"));
    }

    @Test
    @DisplayName("A blank source currency is treated as the base currency")
    public void blankSourceIsBase() {
        assertEquals(new BigDecimal("10"), converter.convert(new BigDecimal("10"), null, "USD"));
    }

    @Test
    @DisplayName("Currency codes are matched case-insensitively and trimmed")
    public void caseAndWhitespaceInsensitive() {
        assertEquals(0, new BigDecimal("833.00000000")
                .compareTo(converter.convert(new BigDecimal("10"), "usd", " inr ")));
    }

    @Test
    @DisplayName("toLedgerPrice rounds a converted unit price to two decimals, half up")
    public void ledgerRounding() {
        assertEquals(new BigDecimal("16.82"), converter.toLedgerPrice(new BigDecimal("1401.00"), "INR", "USD"));
    }

    @Test
    @DisplayName("Setting null rates leaves an empty map, so every conversion is refused")
    public void nullRatesSafe() {
        converter.setRates(null);
        assertTrue(converter.getRates().isEmpty());
        assertThrows(UnsupportedCurrencyException.class,
                () -> converter.convert(new BigDecimal("5"), "EUR", "USD"));
    }
}
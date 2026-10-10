package com.trading.tradeapi.service;

import com.trading.tradeapi.exception.UnsupportedCurrencyException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts a native Fauxnance quote into the account's ledger currency.
 *
 * <p>Fauxnance publishes no FX endpoint, so rates are reference values held in
 * configuration: {@code fx.rates} is quoted as <em>units of a currency per one
 * unit of the base currency</em> (USD), which mirrors the executor service and the
 * frontend currency service so the ticket, the admission check and the eventual
 * fill all agree on the same number.
 */
@Component
@ConfigurationProperties(prefix = "fx")
public class CurrencyConverter {

    private String base = "USD";
    private Map<String, BigDecimal> rates = new HashMap<>();

    public void setBase(String base) {
        this.base = base;
    }

    public void setRates(Map<String, BigDecimal> rates) {
        this.rates = rates == null ? new HashMap<>() : new HashMap<>(rates);
    }

    public String getBase() {
        return base;
    }

    public Map<String, BigDecimal> getRates() {
        return rates;
    }

    /** Convert an amount between two currencies; identity when the codes match. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            return null;
        }
        String source = normalize(from);
        String target = normalize(to);
        if (source.equals(target)) {
            return amount;
        }
        return amount.multiply(rate(target)).divide(rate(source), 8, RoundingMode.HALF_UP);
    }

    /**
     * Rounds a converted unit price to the two decimal places the ledger stores.
     */
    public BigDecimal toLedgerPrice(BigDecimal amount, String from, String to) {
        BigDecimal converted = convert(amount, from, to);
        return converted == null ? null : converted.setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Resolves a configured rate.
     *
     * <p>A missing rate is refused rather than defaulted to one. Treating an absent
     * rate as 1.0 would price a market order, and check its affordability, on a
     * fictitious number, so the order would be admitted or refused on a conversion
     * nobody performed.
     */
    private BigDecimal rate(String code) {
        BigDecimal value = rates.get(code);
        if (value == null || value.signum() == 0) {
            throw new UnsupportedCurrencyException(code);
        }
        return value;
    }

    private String normalize(String code) {
        return (code == null || code.isBlank()) ? base.toUpperCase() : code.trim().toUpperCase();
    }
}
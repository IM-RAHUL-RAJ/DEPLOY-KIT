package com.trade.executor.service;

import com.trade.executor.domain.Quote;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/**
 * Converts money between currencies using a reference rate table.
 *
 * Rates are expressed as units of a currency per one unit of {@link #base}
 * (USD), so {@code convert(1035, "INR", "USD") == 1035 / 83.3}. The account
 * ledger is kept in the account currency (USD), while instruments such as
 * Indian equities quote in their native currency. The executor re-denominates
 * a quote into the account currency before it decides a fill or settles it, so
 * the limit comparison, cash movement and holdings all use the same unit.
 *
 * This is deliberately configuration rather than a live FX feed: Fauxnance
 * exposes no FX endpoint. The rates mirror the frontend display rates.
 */
@Component
@ConfigurationProperties(prefix = "fx")
public class CurrencyConverter {

    private String base = "USD";
    private Map<String, BigDecimal> rates = new HashMap<>();

    public String getBase() {
        return base;
    }

    public void setBase(String base) {
        this.base = base;
    }

    public Map<String, BigDecimal> getRates() {
        return rates;
    }

    public void setRates(Map<String, BigDecimal> rates) {
        this.rates = (rates != null) ? rates : new HashMap<>();
    }

    /** Convert an amount from one currency to another; identity when from == to. */
    public BigDecimal convert(BigDecimal amount, String from, String to) {
        if (amount == null) {
            return null;
        }
        String source = normalize(from);
        String target = normalize(to);
        if (source.equals(target)) {
            return amount;
        }
        return amount.multiply(rate(target))
                .divide(rate(source), 8, RoundingMode.HALF_UP);
    }

    /** Re-denominate every money field of a quote into the account currency. */
    public Quote toAccountCurrency(Quote quote, String accountCurrency) {
        if (quote == null) {
            return null;
        }
        String target = normalize(accountCurrency);
        String source = normalize(quote.currency());
        if (source.equals(target)) {
            return quote;
        }
        return new Quote(
                quote.symbol(),
                convert(quote.price(), source, target),
                convert(quote.bid(), source, target),
                convert(quote.ask(), source, target),
                target,
                convert(quote.change(), source, target),
                quote.changePercent(),
                convert(quote.previousClose(), source, target),
                quote.marketState(),
                quote.stale(),
                quote.quoteAsOf());
    }

    private BigDecimal rate(String code) {
        BigDecimal value = rates.get(code);
        return (value == null || value.signum() == 0) ? BigDecimal.ONE : value;
    }

    private String normalize(String code) {
        return (code == null || code.isBlank()) ? base.toUpperCase() : code.trim().toUpperCase();
    }
}

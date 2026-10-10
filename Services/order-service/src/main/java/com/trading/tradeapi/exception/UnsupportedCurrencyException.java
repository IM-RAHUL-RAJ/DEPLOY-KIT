package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a conversion needs an exchange rate the service does not hold.
 *
 * <p>Pricing a market order for an account whose currency has no configured rate
 * cannot be done correctly, and quietly treating the missing rate as 1.0 would
 * admit or refuse the order on a fictitious number. The order is refused instead,
 * naming the currency, so the rate can be configured.
 */
public class UnsupportedCurrencyException extends DomainException {

    public UnsupportedCurrencyException(String currency) {
        super("FX-422",
                "No exchange rate is configured for " + currency.toUpperCase()
                        + ". The order cannot be priced in that currency.",
                HttpStatus.UNPROCESSABLE_ENTITY);
    }
}
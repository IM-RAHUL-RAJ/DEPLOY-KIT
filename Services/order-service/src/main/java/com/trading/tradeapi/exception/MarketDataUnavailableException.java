package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

/**
 * Raised when a market order cannot be priced because no usable live quote is
 * available. Distinct from {@link InsufficientFundsException}: the order is not
 * rejected for the client's balance but because the market data is unusable.
 */
public class MarketDataUnavailableException extends DomainException {
    public MarketDataUnavailableException(String symbol) {
        super("MKT-503", "No live market price is available for " + symbol, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
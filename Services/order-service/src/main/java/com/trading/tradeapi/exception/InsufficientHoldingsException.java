package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class InsufficientHoldingsException extends DomainException {
    public InsufficientHoldingsException() {
        super("ORD-409", "Insufficient holdings", HttpStatus.CONFLICT);
    }
}

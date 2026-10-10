package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class InstrumentNotFoundException extends DomainException {
    public InstrumentNotFoundException() {
        super("INS-404", "Instrument not found or not tradable", HttpStatus.NOT_FOUND);
    }
}

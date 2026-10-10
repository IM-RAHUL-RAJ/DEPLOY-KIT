package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class DuplicateOrderException extends DomainException {
    public DuplicateOrderException() {
        super("ORD-409", "Duplicate order", HttpStatus.CONFLICT);
    }

    public DuplicateOrderException(String code, String message) {
        super(code, message, HttpStatus.CONFLICT);
    }
}

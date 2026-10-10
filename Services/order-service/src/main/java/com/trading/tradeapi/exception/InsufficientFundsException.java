package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class InsufficientFundsException extends DomainException {
    public InsufficientFundsException() {
        super("ORD-400", "Insufficient funds", HttpStatus.BAD_REQUEST);
    }
}

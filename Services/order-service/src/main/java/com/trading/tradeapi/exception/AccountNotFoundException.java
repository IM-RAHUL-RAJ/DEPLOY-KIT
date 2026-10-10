package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class AccountNotFoundException extends DomainException {
    public AccountNotFoundException() {
        super("ACC-404", "Account not found", HttpStatus.NOT_FOUND);
    }
}

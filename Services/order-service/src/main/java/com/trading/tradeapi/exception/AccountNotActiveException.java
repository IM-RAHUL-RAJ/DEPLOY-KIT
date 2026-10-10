package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class AccountNotActiveException extends DomainException {
    public AccountNotActiveException() {
        super("ACC-403", "Account is not active", HttpStatus.FORBIDDEN);
    }
}

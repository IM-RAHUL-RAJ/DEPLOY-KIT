package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class UnauthorizedAccountAccessException extends DomainException {
    public UnauthorizedAccountAccessException() {
        super("ACC-403", "This account is not the one this session may access", HttpStatus.FORBIDDEN);
    }
}

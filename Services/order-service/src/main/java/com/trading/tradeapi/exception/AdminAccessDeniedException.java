package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class AdminAccessDeniedException extends DomainException {
    public AdminAccessDeniedException() {
        super("AUTH-403", "Administrator privileges are required", HttpStatus.FORBIDDEN);
    }
}

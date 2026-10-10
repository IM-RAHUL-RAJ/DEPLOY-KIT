package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

/** Raised when an admin route names a user id that does not exist. */
public class UserNotFoundException extends DomainException {
    public UserNotFoundException() {
        super("USR-404", "User not found", HttpStatus.NOT_FOUND);
    }
}
package com.trading.tradeapi.exception;

import org.springframework.http.HttpStatus;

public class OrderNotFoundException extends DomainException {
    public OrderNotFoundException() {
        super("ORD-404", "Order not found", HttpStatus.NOT_FOUND);
    }

    public OrderNotFoundException(String message) {
        super("ORD-404", message, HttpStatus.NOT_FOUND);
    }
}

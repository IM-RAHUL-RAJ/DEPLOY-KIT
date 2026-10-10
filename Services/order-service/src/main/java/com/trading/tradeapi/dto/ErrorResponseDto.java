package com.trading.tradeapi.dto;

public record ErrorResponseDto(
        String errorCode,
        String message
) {}

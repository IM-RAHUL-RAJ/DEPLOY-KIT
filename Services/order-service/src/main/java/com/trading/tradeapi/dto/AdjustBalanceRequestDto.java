package com.trading.tradeapi.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AdjustBalanceRequestDto(
        @NotNull(message = "amount is required")
        BigDecimal amount,
        String reason
) {}

package com.trading.tradeapi.dto;

import java.math.BigDecimal;

public record AdminAccountDto(
        Long accountId,
        Long userId,
        String holderName,
        String username,
        String userType,
        String currency,
        BigDecimal cashBalance,
        String status,
        Long version
) {}

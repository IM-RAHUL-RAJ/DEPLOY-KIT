package com.trading.tradeapi.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.trading.tradeapi.enums.AccountStatus;

import java.math.BigDecimal;
import java.time.Instant;

public record AccountResponseDto(
        Long id,
        String accountId,
        String holderName,
        BigDecimal cashBalance,
        String currency,
        AccountStatus status,
        Long version,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant lastUpdated
) {}

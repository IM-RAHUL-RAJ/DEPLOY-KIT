package com.trading.tradeapi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateInstrumentRequestDto(
        @NotBlank(message = "symbol is required")
        @Size(min = 1, max = 128, message = "symbol must be between 1 and 128 characters")
        String symbol,
        String ticker,
        String assetClass,
        String quoteCurrency,
        String status
) {}

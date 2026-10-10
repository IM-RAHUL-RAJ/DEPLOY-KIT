package com.trading.tradeapi.watchlist.alerts.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * The body that arms a one-shot price alert. The symbols survive validation in the service,
 * where the instrument catalogue is reachable; this record only checks shapes.
 */
public record CreatePriceAlertRequest(
        @NotBlank(message = "symbol is required")
        @Size(max = 20, message = "symbol is too long")
        String symbol,
        @NotBlank(message = "direction is required")
        @Pattern(regexp = "ABOVE|BELOW", message = "direction must be ABOVE or BELOW")
        String direction,
        @NotNull(message = "threshold is required")
        @DecimalMin(value = "0.0", inclusive = false, message = "threshold must be greater than zero")
        BigDecimal threshold) {
}
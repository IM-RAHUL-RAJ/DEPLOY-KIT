package com.trading.tradeapi.strategy.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Pause ({@code DISABLED}) or resume ({@code ACTIVE}) a strategy. */
public record UpdateStrategyStatusRequest(
        @NotBlank @Pattern(regexp = "(?i)^(ACTIVE|DISABLED)$", message = "status must be ACTIVE or DISABLED")
        String status
) {}

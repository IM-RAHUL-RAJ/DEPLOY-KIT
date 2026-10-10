package com.trading.tradeapi.strategy.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * A new strategy.
 *
 * <p>The simple one, {@code PRICE_TARGET}, needs only the instrument, the side, the target
 * price and the quantity (and optionally MARKET or LIMIT): it trades once when the price
 * reaches the target. The indicator rules ({@code MA_CROSSOVER}, {@code BOLLINGER}) also
 * need a maximum order value. Everything else is optional with a standard default: name
 * from the rule, windows 20/50, bands 20 days at 2 standard deviations, five orders a day,
 * and a maximum position of five orders' worth.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CreateStrategyRequest(
        @Size(max = 60) String name,
        @NotBlank @Pattern(regexp = "(?i)^[A-Z0-9.:\\-]{1,20}$",
                message = "symbol must be 1 to 20 characters of A-Z, 0-9, '.', ':' or '-'")
        String symbol,
        @NotBlank @Pattern(regexp = "(?i)^(PRICE_TARGET|MA_CROSSOVER|BOLLINGER)$",
                message = "strategyType must be PRICE_TARGET, MA_CROSSOVER or BOLLINGER")
        String strategyType,
        @Pattern(regexp = "(?i)^(BUY|SELL)$", message = "side must be BUY or SELL") String side,
        @DecimalMin("0.0001") @DecimalMax("100000000") BigDecimal targetPrice,
        @Pattern(regexp = "(?i)^(MARKET|LIMIT)$", message = "orderType must be MARKET or LIMIT") String orderType,
        @Min(2) @Max(100) Integer shortWindow,
        @Min(3) @Max(200) Integer longWindow,
        @Min(5) @Max(100) Integer bandPeriod,
        @DecimalMin("0.5") @DecimalMax("4.0") BigDecimal bandK,
        @NotNull @Min(1) @Max(1_000_000) Long quantity,
        @DecimalMin("0.01") @DecimalMax("100000000") BigDecimal maxNotional,
        @Min(1) @Max(20) Integer maxOrdersPerDay,
        @Min(1) @Max(10_000_000) Long maxPosition
) {}

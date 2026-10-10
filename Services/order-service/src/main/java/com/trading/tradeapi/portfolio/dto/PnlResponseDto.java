package com.trading.tradeapi.portfolio.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * The P&amp;L answer. {@code from}/{@code to} bound the realised figure only; unrealised profit
 * and loss is always as at {@code asOf}, because it is a function of the current price.
 */
public record PnlResponseDto(
        long accountId,
        String baseCurrency,
        LocalDate from,
        LocalDate to,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        BigDecimal totalPnl,
        List<SymbolPnlDto> bySymbol,
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
        Instant asOf) {
}
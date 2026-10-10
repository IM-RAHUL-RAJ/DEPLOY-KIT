package com.trading.tradeapi.portfolio.dto;

import java.math.BigDecimal;

/**
 * One instrument's contribution to the P&amp;L, in the account's ledger currency.
 */
public record SymbolPnlDto(
        String symbol,
        BigDecimal realisedPnl,
        BigDecimal unrealisedPnl,
        BigDecimal totalPnl) {
}
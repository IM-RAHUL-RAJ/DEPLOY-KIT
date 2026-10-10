package com.trading.tradeapi.dto;

import java.math.BigDecimal;

public record AdminStatsDto(
        long totalUsers,
        long totalAccounts,
        long activeAccounts,
        long pendingAccounts,
        long suspendedAccounts,
        long totalOrders,
        long newOrders,
        long filledOrders,
        long rejectedOrders,
        long cancelledOrders,
        long totalInstruments,
        BigDecimal totalCash
) {}

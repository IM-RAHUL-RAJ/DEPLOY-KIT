package com.trading.tradeapi.chatbot.dto;

import java.math.BigDecimal;

public record OrderConfirmationData(
        String instrument,
        String symbol,
        String side,           // "BUY" or "SELL"
        BigDecimal quantity,
        String orderType,      // "MARKET" or "LIMIT"
        BigDecimal price,      // null for market orders
        BigDecimal estimatedCost,
        String userConfirmationQuestion
) {}

package com.trading.tradeapi.chatbot.dto;

import java.math.BigDecimal;

/**
 * Structured output from Groq LLM for order extraction.
 */
public record LlmOrderExtractionDto(
        String symbol,         // Extracted instrument symbol (e.g., "AAPL")
        String side,           // "BUY" or "SELL"
        BigDecimal quantity,   // Order quantity
        String orderType,      // "MARKET" or "LIMIT"
        BigDecimal limitPrice, // Price for limit orders (null for market)
        Boolean sellAll,       // true if user said "sell all"
        String confidence      // "HIGH", "MEDIUM", "LOW"
) {}

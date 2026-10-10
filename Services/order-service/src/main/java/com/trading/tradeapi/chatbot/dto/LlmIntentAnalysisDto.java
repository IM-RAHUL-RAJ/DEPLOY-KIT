package com.trading.tradeapi.chatbot.dto;

import java.util.List;

/**
 * Structured output from Groq LLM for intent classification and entity extraction.
 */
public record LlmIntentAnalysisDto(
        String intent,         // "ACCOUNT_QUERY", "PORTFOLIO_QUERY", "INSTRUMENT_QUERY", "ORDER_REQUEST", "UNSUPPORTED"
        String confidence,     // "HIGH", "MEDIUM", "LOW"
        List<String> entities, // Extracted entities (e.g., symbols, quantities)
        String extractedQuery, // The refined query for backend processing
        String riskFlags       // Comma-separated list: "none", "dangerous", "admin_access", "other_account"
) {}

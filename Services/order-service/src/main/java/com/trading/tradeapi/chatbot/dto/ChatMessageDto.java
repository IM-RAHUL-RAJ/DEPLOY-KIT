package com.trading.tradeapi.chatbot.dto;

import java.time.Instant;

public record ChatMessageDto(
        String role,           // "user" or "assistant"
        String content,
        Instant timestamp,
        String messageType     // "text", "order_confirmation", "error"
) {}

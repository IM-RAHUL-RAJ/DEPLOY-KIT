package com.trading.tradeapi.chatbot.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChatRequestDto(
        @NotBlank(message = "Message cannot be empty")
        String message,
        List<ChatMessageDto> conversationHistory,
        String confirmationToken  // For order confirmation
) {}

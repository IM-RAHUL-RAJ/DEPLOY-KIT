package com.trading.tradeapi.chatbot.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatResponseDto(
        String response,
        String messageType,    // "text", "order_confirmation", "order_success", "error"
        Instant timestamp,
        OrderConfirmationData orderConfirmation,  // Present if messageType = "order_confirmation"
        String confirmationToken,
        Boolean success
) {}

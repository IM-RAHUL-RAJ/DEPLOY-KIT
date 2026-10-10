package com.trading.tradeapi.chatbot.controller;

import com.trading.tradeapi.chatbot.dto.ChatRequestDto;
import com.trading.tradeapi.chatbot.dto.ChatResponseDto;
import com.trading.tradeapi.chatbot.service.ChatbotService;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoint for the AI chatbot.
 * Requires authentication via JWT token.
 */
@RestController
@RequestMapping("/api/v1/chatbot")
public class ChatbotController {

    private static final Logger logger = LoggerFactory.getLogger(ChatbotController.class);

    private final ChatbotService chatbotService;

    public ChatbotController(ChatbotService chatbotService) {
        this.chatbotService = chatbotService;
    }

    /**
     * Main chat endpoint
     * POST /api/v1/chatbot/chat
     */
    @PostMapping("/chat")
    public ResponseEntity<ChatResponseDto> chat(
            @Valid @RequestBody ChatRequestDto request,
            HttpServletRequest httpRequest) {

        Long authenticatedAccountId = (Long) httpRequest.getAttribute(
                JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);

        if (authenticatedAccountId == null) {
            ChatResponseDto errorResponse = new ChatResponseDto(
                    "Authentication required",
                    "error",
                    java.time.Instant.now(),
                    null,
                    null,
                    false
            );
            return ResponseEntity.status(401).body(errorResponse);
        }

        try {
            ChatResponseDto response = chatbotService.chat(authenticatedAccountId, request);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("Error processing chat request: {}", e.getMessage(), e);
            ChatResponseDto errorResponse = new ChatResponseDto(
                    "An error occurred processing your request",
                    "error",
                    java.time.Instant.now(),
                    null,
                    null,
                    false
            );
            return ResponseEntity.status(500).body(errorResponse);
        }
    }
}

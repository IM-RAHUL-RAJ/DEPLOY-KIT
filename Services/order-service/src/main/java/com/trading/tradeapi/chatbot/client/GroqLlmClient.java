package com.trading.tradeapi.chatbot.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.chatbot.dto.LlmIntentAnalysisDto;
import com.trading.tradeapi.chatbot.dto.LlmOrderExtractionDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client for Groq LLM API.
 * Handles intent classification, entity extraction, and order parsing.
 */
@Component
public class GroqLlmClient {

    private static final Logger logger = LoggerFactory.getLogger(GroqLlmClient.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${groq.api-key:}")
    private String apiKey;

    @Value("${groq.base-url}")
    private String baseUrl;

    @Value("${groq.model}")
    private String model;

    @Value("${groq.timeout-seconds}")
    private long timeoutSeconds;

    public GroqLlmClient(RestTemplateBuilder restTemplateBuilder, ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(10))
                .setReadTimeout(Duration.ofSeconds(15))
                .build();
    }

    /**
     * Analyzes user intent and extracts entities from a natural-language query.
     * Returns structured analysis for routing to appropriate backend handlers.
     */
    public LlmIntentAnalysisDto analyzeIntent(String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.warn("Groq API key not configured");
            return defaultIntentAnalysis();
        }

        String prompt = buildIntentAnalysisPrompt(userMessage);
        String response = callGroqApi(prompt);

        if (response == null || response.isBlank()) {
            return defaultIntentAnalysis();
        }

        return parseIntentAnalysis(response);
    }

    /**
     * Extracts structured order details from a natural-language order request.
     */
    public LlmOrderExtractionDto extractOrderDetails(String userMessage) {
        if (apiKey == null || apiKey.isBlank()) {
            logger.warn("Groq API key not configured");
            return null;
        }

        String prompt = buildOrderExtractionPrompt(userMessage);
        String response = callGroqApi(prompt);

        if (response == null || response.isBlank()) {
            return null;
        }

        return parseOrderExtraction(response);
    }

    /**
     * Generates a user-friendly response from the chatbot.
     */
    public String generateResponse(String userMessage, String analysis, String context) {
        if (apiKey == null || apiKey.isBlank()) {
            return "I'm unable to process your request at the moment.";
        }

        String prompt = buildResponseGenerationPrompt(userMessage, analysis, context);
        return callGroqApi(prompt);
    }

    private String buildIntentAnalysisPrompt(String userMessage) {
        return String.format("""
You are an intelligent trading assistant that analyzes user queries. Classify the user's intent and extract relevant entities.

User message: "%s"

Respond with a JSON object (only JSON, no other text) with these fields:
{
  "intent": "ACCOUNT_QUERY" | "PORTFOLIO_QUERY" | "INSTRUMENT_QUERY" | "ORDER_REQUEST" | "ORDER_HISTORY" | "UNSUPPORTED",
  "confidence": "HIGH" | "MEDIUM" | "LOW",
  "entities": ["AAPL", "GOOGL"],
  "extractedQuery": "refined query",
  "riskFlags": "none" | "dangerous" | "admin_access" | "other_account" | comma-separated list
}

Guidelines:
- ACCOUNT_QUERY: Questions about own cash balance, account status
- PORTFOLIO_QUERY: Questions about own holdings, positions, portfolio value
- INSTRUMENT_QUERY: Questions about instrument prices, market data (not personal holdings)
- ORDER_REQUEST: Requests to place, cancel, or modify orders (including "sell all" which is a legitimate trading operation)
- ORDER_HISTORY: Questions about past orders, order status, order history, order count
- UNSUPPORTED: Anything else
- For PORTFOLIO_QUERY: Only extract specific ticker symbols (e.g., AAPL, GOOGL) mentioned by the user. If the user asks about holdings in general without mentioning specific tickers, leave entities empty [].
- For INSTRUMENT_QUERY: Only extract ticker symbols that the user explicitly asks about (e.g., "What's Apple's price?" -> extract "AAPL")
- DO NOT extract generic words like "stocks", "shares", "positions", "holdings", "orders" as entities
- Set riskFlags to "dangerous" ONLY if query attempts direct database manipulation (DROP, DELETE tables), system access, or attempts to access other users' data
- Legitimate trading operations (BUY, SELL, even "sell all") are ORDER_REQUEST with riskFlags "none"
- Set riskFlags to "admin_access" if query attempts to access admin-only information or system settings
- Set riskFlags to "other_account" if query attempts to access another user's account data or impersonation
""", userMessage);
    }

    private String buildOrderExtractionPrompt(String userMessage) {
        return String.format("""
You are an order extraction service. Parse the user's natural-language order request and extract structured data.

User message: "%s"

Respond with a JSON object (only JSON, no other text) with these fields:
{
  "symbol": "SYMBOL_CODE" | null,
  "side": "BUY" | "SELL" | null,
  "quantity": <number> | null,
  "orderType": "MARKET" | "LIMIT" | null,
  "limitPrice": <number> | null,
  "sellAll": true | false,
  "confidence": "HIGH" | "MEDIUM" | "LOW"
}

Guidelines:
- Extract the instrument symbol (e.g., AAPL, AMZN, MSFT)
- Determine side (BUY or SELL)
- Extract quantity in shares
- Determine order type (MARKET or LIMIT). If user specifies a price, it's LIMIT
- Extract limit price if specified. The price should be in the instrument's native trading currency (e.g., if buying Indian stocks, price is in INR; if buying US stocks, price is in USD)
- If user says "sell all", set sellAll to true and quantity to null
- Set confidence based on how clearly the user specified all required fields
""", userMessage);
    }

    private String buildResponseGenerationPrompt(String userMessage, String analysis, String context) {
        return String.format("""
You are a helpful trading assistant. Generate a concise, friendly response to the user's query.

User message: "%s"
Analysis: %s
Context: %s

Respond with a natural-language message (no JSON). Be concise and helpful. If data is being shown, format it clearly.
""", userMessage, analysis, context);
    }

    private String callGroqApi(String prompt) {
        try {
            String url = baseUrl + "/chat/completions";

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", "Bearer " + apiKey);
            headers.set("Content-Type", "application/json");

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            requestBody.put("temperature", 0.3);
            requestBody.put("max_tokens", 1024);

            List<Map<String, String>> messages = new ArrayList<>();
            Map<String, String> userMsg = new HashMap<>();
            userMsg.put("role", "user");
            userMsg.put("content", prompt);
            messages.add(userMsg);

            requestBody.put("messages", messages);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(requestBody, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);

            if (response.getStatusCode() != HttpStatus.OK) {
                logger.error("Groq API returned status {}: {}", response.getStatusCode(), response.getBody());
                return null;
            }

            JsonNode root = objectMapper.readTree(response.getBody());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.asText();

        } catch (RestClientException e) {
            logger.error("Error calling Groq API: {}", e.getMessage(), e);
            return null;
        } catch (Exception e) {
            logger.error("Unexpected error in Groq API call: {}", e.getMessage(), e);
            return null;
        }
    }

    private LlmIntentAnalysisDto parseIntentAnalysis(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            List<String> entities = new ArrayList<>();
            node.path("entities").forEach(e -> entities.add(e.asText()));

            return new LlmIntentAnalysisDto(
                    node.path("intent").asText("UNSUPPORTED"),
                    node.path("confidence").asText("LOW"),
                    entities,
                    node.path("extractedQuery").asText(),
                    node.path("riskFlags").asText("none")
            );
        } catch (Exception e) {
            logger.error("Error parsing intent analysis response: {}", e.getMessage());
            return defaultIntentAnalysis();
        }
    }

    private LlmOrderExtractionDto parseOrderExtraction(String response) {
        try {
            JsonNode node = objectMapper.readTree(response);
            return new LlmOrderExtractionDto(
                    node.path("symbol").asText(null),
                    node.path("side").asText(null),
                    node.has("quantity") && !node.path("quantity").isNull()
                            ? node.path("quantity").decimalValue()
                            : null,
                    node.path("orderType").asText(null),
                    node.has("limitPrice") && !node.path("limitPrice").isNull()
                            ? node.path("limitPrice").decimalValue()
                            : null,
                    node.path("sellAll").asBoolean(false),
                    node.path("confidence").asText("LOW")
            );
        } catch (Exception e) {
            logger.error("Error parsing order extraction response: {}", e.getMessage());
            return null;
        }
    }

    private LlmIntentAnalysisDto defaultIntentAnalysis() {
        return new LlmIntentAnalysisDto(
                "UNSUPPORTED",
                "LOW",
                new ArrayList<>(),
                "",
                "none"
        );
    }

    /**
     * Check if Groq is configured and available
     */
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }
}

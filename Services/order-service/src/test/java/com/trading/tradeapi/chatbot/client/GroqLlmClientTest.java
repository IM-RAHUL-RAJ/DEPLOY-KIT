package com.trading.tradeapi.chatbot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.chatbot.dto.LlmIntentAnalysisDto;
import com.trading.tradeapi.chatbot.dto.LlmOrderExtractionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("GroqLlmClient")
class GroqLlmClientTest {

    private RestTemplate rest;
    private GroqLlmClient client;

    @BeforeEach
    void setUp() {
        rest = mock(RestTemplate.class);
        RestTemplateBuilder builder = mock(RestTemplateBuilder.class);
        when(builder.setConnectTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.setReadTimeout(any(Duration.class))).thenReturn(builder);
        when(builder.build()).thenReturn(rest);
        client = new GroqLlmClient(builder, new ObjectMapper());
        ReflectionTestUtils.setField(client, "baseUrl", "https://groq.test/v1");
        ReflectionTestUtils.setField(client, "model", "test-model");
    }

    /** The model's reply, wrapped the way the chat-completions API returns it. */
    private void replies(String content) throws Exception {
        String body = new ObjectMapper().writeValueAsString(
                java.util.Map.of("choices", java.util.List.of(java.util.Map.of("message", java.util.Map.of("content", content)))));
        when(rest.postForEntity(eq("https://groq.test/v1/chat/completions"), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok(body));
    }

    @Nested
    @DisplayName("Without an API key")
    class WithoutAKey {

        @Test
        @DisplayName("Is unavailable and answers safe defaults without calling out")
        void defaults() {
            ReflectionTestUtils.setField(client, "apiKey", "");
            assertThat(client.isAvailable()).isFalse();
            assertThat(client.analyzeIntent("balance?").intent()).isEqualTo("UNSUPPORTED");
            assertThat(client.extractOrderDetails("buy 5 AAPL")).isNull();
            assertThat(client.generateResponse("hi", "a", "c")).contains("unable");
            ReflectionTestUtils.setField(client, "apiKey", null);
            assertThat(client.isAvailable()).isFalse();
            verifyNoInteractions(rest);
        }
    }

    @Nested
    @DisplayName("With an API key")
    class WithAKey {

        @BeforeEach
        void key() {
            ReflectionTestUtils.setField(client, "apiKey", "gsk-test");
        }

        @Test
        @DisplayName("Reads the intent and its entities from the model's JSON")
        void intent() throws Exception {
            replies("{\"intent\":\"PORTFOLIO_QUERY\",\"confidence\":\"HIGH\",\"entities\":[\"AAPL\"],"
                    + "\"extractedQuery\":\"holdings\",\"riskFlags\":\"none\"}");
            LlmIntentAnalysisDto intent = client.analyzeIntent("How much AAPL do I hold?");
            assertThat(client.isAvailable()).isTrue();
            assertThat(intent.intent()).isEqualTo("PORTFOLIO_QUERY");
            assertThat(intent.entities()).containsExactly("AAPL");
        }

        @Test
        @DisplayName("Reads an order, with null quantity and price when the model gives none")
        void order() throws Exception {
            replies("{\"symbol\":\"AAPL\",\"side\":\"BUY\",\"quantity\":5,\"orderType\":\"LIMIT\",\"limitPrice\":190.5,"
                    + "\"sellAll\":false,\"confidence\":\"HIGH\"}");
            LlmOrderExtractionDto order = client.extractOrderDetails("buy 5 AAPL at 190.5");
            assertThat(order.quantity()).isEqualByComparingTo("5");
            assertThat(order.limitPrice()).isEqualByComparingTo("190.5");

            replies("{\"symbol\":\"AAPL\",\"side\":\"SELL\",\"quantity\":null,\"sellAll\":true}");
            LlmOrderExtractionDto sellAll = client.extractOrderDetails("sell all my AAPL");
            assertThat(sellAll.quantity()).isNull();
            assertThat(sellAll.limitPrice()).isNull();
            assertThat(sellAll.sellAll()).isTrue();
            assertThat(sellAll.confidence()).isEqualTo("LOW");
        }

        @Test
        @DisplayName("Returns the model's text as the reply")
        void response() throws Exception {
            replies("You hold 5 AAPL.");
            assertThat(client.generateResponse("holdings?", "PORTFOLIO_QUERY", "AAPL 5")).isEqualTo("You hold 5 AAPL.");
        }

        @Test
        @DisplayName("Text that is not JSON falls back to the defaults")
        void unparseable() throws Exception {
            replies("Sorry, I cannot do JSON");
            assertThat(client.analyzeIntent("x").intent()).isEqualTo("UNSUPPORTED");
            assertThat(client.extractOrderDetails("x")).isNull();
        }

        @Test
        @DisplayName("An empty reply, an error status or an unreachable API answers the defaults")
        void failures() {
            when(rest.postForEntity(any(String.class), any(), eq(String.class)))
                    .thenReturn(ResponseEntity.ok("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"))
                    .thenReturn(ResponseEntity.ok("{\"choices\":[{\"message\":{\"content\":\"\"}}]}"))
                    .thenReturn(ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body("slow down"))
                    .thenThrow(new ResourceAccessException("timeout"))
                    .thenReturn(ResponseEntity.ok("not json"));

            assertThat(client.analyzeIntent("x").intent()).isEqualTo("UNSUPPORTED");
            assertThat(client.extractOrderDetails("x")).isNull();
            assertThat(client.analyzeIntent("x").intent()).isEqualTo("UNSUPPORTED");
            assertThat(client.extractOrderDetails("x")).isNull();
            assertThat(client.generateResponse("x", "a", "c")).isNull();
        }
    }
}

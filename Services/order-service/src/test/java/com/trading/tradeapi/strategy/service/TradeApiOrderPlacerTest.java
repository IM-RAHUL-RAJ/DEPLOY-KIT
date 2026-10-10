package com.trading.tradeapi.strategy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("TradeApiOrderPlacer")
class TradeApiOrderPlacerTest {

    private static final String SECRET = "test-secret-that-is-at-least-32-bytes-long-abc";

    private RestTemplate http;
    private MockEnvironment env;
    private TradeApiOrderPlacer placer;

    @BeforeEach
    void setUp() {
        env = new MockEnvironment();
        // What Config/application.yml supplies: this service's own address and port.
        env.setProperty("services.order.url", "http://order.test");
        env.setProperty("server.port", "8081");
        placer = new TradeApiOrderPlacer(new ObjectMapper(), env, SECRET, "auth-service", 30000);
        http = mock(RestTemplate.class);
        ReflectionTestUtils.setField(placer, "http", http);
    }

    private StrategyOrderPlacer.PlacedOrder place(String type, BigDecimal limit) {
        return placer.place(7L, 1L, "AAPL", "BUY", 2L, type, limit, "strategy-7-AAPL@t");
    }

    @Nested
    @DisplayName("Where orders go")
    class Url {

        @Test
        @DisplayName("The configured API, else this service (Config services.order.url) on the port it bound")
        void resolves() {
            assertThat(placer.ordersUrl()).isEqualTo("http://order.test:8081/api/v1/orders");
            env.setProperty("local.server.port", "9090");
            assertThat(placer.ordersUrl()).isEqualTo("http://order.test:9090/api/v1/orders");
            env.setProperty("strategy.trade-api-url", "https://gateway.test//");
            assertThat(placer.ordersUrl()).isEqualTo("https://gateway.test/api/v1/orders");
            env.setProperty("strategy.trade-api-url", " ");
            assertThat(placer.ordersUrl()).isEqualTo("http://order.test:9090/api/v1/orders");
        }
    }

    @Nested
    @DisplayName("Placing")
    class Placing {

        @Test
        @SuppressWarnings("unchecked")
        @DisplayName("A market order carries no price; a limit order carries the target to the cent")
        void bodies() {
            when(http.postForObject(anyString(), any(), eq(String.class))).thenReturn("{\"orderId\":\"ORD-1\",\"status\":\"NEW\"}");

            assertThat(place("MARKET", null).orderId()).isEqualTo("ORD-1");
            place("LIMIT", new BigDecimal("190.555"));

            ArgumentCaptor<HttpEntity<Map<String, Object>>> sent = ArgumentCaptor.forClass(HttpEntity.class);
            verify(http, org.mockito.Mockito.times(2)).postForObject(anyString(), sent.capture(), eq(String.class));
            assertThat(sent.getAllValues().get(0).getBody()).doesNotContainKey("price").containsEntry("orderType", "MARKET");
            assertThat(sent.getAllValues().get(1).getBody()).containsEntry("price", new BigDecimal("190.56"));
            assertThat(sent.getAllValues().get(1).getHeaders().getFirst("Authorization")).startsWith("Bearer ");
            // A LIMIT with no price is sent as a market order.
            place("LIMIT", null);
        }

        @Test
        @DisplayName("Every failure becomes an OrderPlacementException with a readable reason")
        void failures() {
            when(http.postForObject(anyString(), any(), eq(String.class)))
                    .thenReturn("{\"status\":\"REJECTED\",\"message\":\"Account suspended\"}")
                    .thenReturn(null)
                    .thenThrow(HttpClientErrorException.create(HttpStatus.UNPROCESSABLE_ENTITY, "Unprocessable", null,
                            "{\"message\":\"Price required\"}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null,
                            "not json".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .thenThrow(HttpClientErrorException.create(HttpStatus.CONFLICT, "Conflict", null,
                            "{}".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8))
                    .thenThrow(new ResourceAccessException("refused"))
                    .thenReturn("{broken");

            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("Account suspended");
            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("no reason given");
            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("422: Price required");
            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("403: Forbidden");
            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("409: Conflict");
            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("unreachable");
            assertThatThrownBy(() -> place("MARKET", null)).hasMessageContaining("Unreadable");
        }

        @Test
        @DisplayName("Without a signing secret nothing is sent")
        void noSecret() {
            TradeApiOrderPlacer unsigned = new TradeApiOrderPlacer(new ObjectMapper(), env, "", "auth-service", 30000);
            assertThatThrownBy(() -> unsigned.place(7L, 1L, "AAPL", "BUY", 1L, "MARKET", null, "k"))
                    .hasMessageContaining("No JWT secret");
            assertThatThrownBy(() -> new TradeApiOrderPlacer(new ObjectMapper(), env, null, "a", 30000)
                    .place(7L, 1L, "AAPL", "BUY", 1L, "MARKET", null, "k")).hasMessageContaining("No JWT secret");
        }
    }
}

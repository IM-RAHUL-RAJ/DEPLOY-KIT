package com.trade.executor.kafka;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.FillDecision;
import com.trade.executor.domain.Quote;
import com.trade.executor.exception.NonRetryableOrderException;
import com.trade.executor.exception.RetryableOrderException;
import com.trade.executor.kafka.TradeEventPublisher.OrderFacts;
import com.trade.executor.scheduler.EndOfDayTask;
import com.trade.executor.service.ExecutionService;
import com.trade.executor.service.OrderResolver;
import com.trade.executor.service.QuoteCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The executor's Kafka edges: order events in, trade events out, and the end-of-day sweep. */
@DisplayName("Kafka events and the end-of-day task")
class KafkaEventsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OrderFacts order = new OrderFacts(7L, 1L, "AAPL", "BUY", 2L, new BigDecimal("101"));

    @Nested
    @DisplayName("TradeEventPublisher")
    class Publisher {

        @SuppressWarnings("unchecked")
        private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        private TradeEventPublisher publisher;

        @BeforeEach
        void setUp() {
            publisher = new TradeEventPublisher(kafka, mapper);
            ReflectionTestUtils.setField(publisher, "tradeEventsTopic", "trade-events");
        }

        private JsonNode sent() throws Exception {
            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(kafka).send(eq("trade-events"), eq("1"), json.capture());
            return mapper.readTree(json.getValue());
        }

        @Test
        @DisplayName("A fill is ORDER_FILLED in the five-field envelope, with the position after it")
        void filled() throws Exception {
            when(kafka.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));
            publisher.publishFilled(order,
                    FillDecision.filled(new BigDecimal("100.5"), new BigDecimal("-201"), 2L, new BigDecimal("100.5")));

            JsonNode event = sent();
            assertThat(event.path("eventType").asText()).isEqualTo("ORDER_FILLED");
            assertThat(event.path("source").asText()).isEqualTo("trade-executor");
            assertThat(event.path("payload").path("status").asText()).isEqualTo("FILLED");
            assertThat(event.path("payload").path("positionQuantityAfter").asLong()).isEqualTo(2L);
        }

        @Test
        @DisplayName("A rejection carries its reason and the unchanged position, zero when none")
        void rejected() throws Exception {
            when(kafka.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
            publisher.publishRejected(order, "INSUFFICIENT_FUNDS", null, null);

            JsonNode payload = sent().path("payload");
            assertThat(payload.path("reason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
            assertThat(payload.path("positionQuantityAfter").asLong()).isZero();
            assertThat(payload.path("cashDelta").decimalValue()).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("A send that throws is logged, never thrown back into the fill")
        void sendFailureIsSwallowed() {
            when(kafka.send(anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("no broker"));
            publisher.publishRejected(order, "MARKET_CLOSED", 3L, BigDecimal.ONE);
            verify(kafka).send(anyString(), anyString(), anyString());
        }
    }

    @Nested
    @DisplayName("OrderEventConsumer")
    class Consumer {

        private ExecutionService execution;
        private OrderResolver resolver;
        private QuoteCache cache;
        private FauxnanceClient fauxnance;
        private Acknowledgment ack;
        private OrderEventConsumer consumer;

        @BeforeEach
        void setUp() {
            execution = mock(ExecutionService.class);
            resolver = mock(OrderResolver.class);
            cache = new QuoteCache();
            fauxnance = mock(FauxnanceClient.class);
            ack = mock(Acknowledgment.class);
            consumer = new OrderEventConsumer(mapper, execution, resolver, cache, fauxnance);
        }

        private String placed(String orderId) {
            return "{\"eventType\":\"ORDER_PLACED\",\"payload\":{\"orderId\":\"" + orderId + "\"}}";
        }

        private Map<String, Object> row(String status, String type) {
            Map<String, Object> row = new HashMap<>(Map.of("order_id", 7L, "account_id", 1L, "ticker", "AAPL",
                    "side", "BUY", "quantity", 2L, "price", BigDecimal.TEN, "status", status));
            if (type != null) {
                row.put("order_type", type);
            }
            return row;
        }

        @Test
        @DisplayName("A market order is resolved on arrival with a live quote, else the cached one")
        void marketOrderResolved() {
            Quote cached = new Quote("AAPL", BigDecimal.TEN, null, null, "USD", null, null, null, "open", false, null);
            cache.put("AAPL", cached);
            when(execution.fetchOrder(7L)).thenReturn(row("NEW", "MARKET"));
            when(resolver.resolve(any(), eq("MARKET"), any())).thenReturn(OrderResolver.Outcome.FILLED);

            consumer.onOrderPlaced(placed("ORD-7"), ack);

            verify(resolver).resolve(any(), eq("MARKET"), eq(cached));
            verify(ack).acknowledge();
        }

        @Test
        @DisplayName("A limit order waits for the poller; a settled order and other event types are only acknowledged")
        void notResolvedOnArrival() throws Exception {
            when(execution.fetchOrder(7L)).thenReturn(row("NEW", null), row("FILLED", "MARKET"));
            consumer.onOrderPlaced(placed("7"), ack);
            consumer.onOrderPlaced(placed("7"), ack);
            // A double-encoded event from an older producer is read the same way.
            consumer.onOrderPlaced(mapper.writeValueAsString("{\"eventType\":\"ORDER_CANCELLED\"}"), ack);

            verifyNoInteractions(resolver);
            verify(ack, org.mockito.Mockito.times(3)).acknowledge();
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"not json", "{\"eventType\":\"ORDER_PLACED\",\"payload\":{\"orderId\":\"x\"}}",
                "{\"eventType\":\"ORDER_PLACED\",\"payload\":{\"orderId\":\"0\"}}"})
        @DisplayName("A malformed event can never succeed: straight to the dead-letter topic")
        void malformed(String message) {
            assertThatThrownBy(() -> consumer.onOrderPlaced(message, ack)).isInstanceOf(NonRetryableOrderException.class);
            verify(ack, never()).acknowledge();
        }

        @Test
        @DisplayName("A missing row or a database blip is retried")
        void retryable() {
            when(execution.fetchOrder(7L)).thenReturn(null).thenThrow(new QueryTimeoutException("slow"));
            assertThatThrownBy(() -> consumer.onOrderPlaced(placed("7"), ack)).isInstanceOf(RetryableOrderException.class);
            assertThatThrownBy(() -> consumer.onOrderPlaced(placed("7"), ack))
                    .isInstanceOf(RetryableOrderException.class).hasMessageContaining("Database unavailable");
        }
    }

    @Nested
    @DisplayName("EndOfDayTask")
    class EndOfDay {

        private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        private final OrderResolver resolver = mock(OrderResolver.class);
        private final EndOfDayTask task = new EndOfDayTask(jdbc, resolver);

        private Map<String, Object> row(long id) {
            return Map.of("order_id", id, "account_id", 1L, "ticker", "AAPL", "side", "BUY",
                    "quantity", 1L, "price", BigDecimal.ONE);
        }

        @Test
        @DisplayName("Every working order is expired as MARKET_CLOSED; one failure does not stop the rest")
        void expiresWorkingOrders() {
            when(jdbc.queryForList(anyString())).thenReturn(List.of(row(1), row(2), row(3)));
            when(resolver.expire(any(), eq("MARKET_CLOSED"))).thenReturn(true, false)
                    .thenThrow(new IllegalStateException("db"));

            task.rejectRemainingNewOrders();

            verify(resolver, org.mockito.Mockito.times(3)).expire(any(), eq("MARKET_CLOSED"));
        }

        @Test
        @DisplayName("Nothing working, or the query failing, is not an error")
        void nothingToDo() {
            when(jdbc.queryForList(anyString())).thenReturn(List.of()).thenThrow(new IllegalStateException("down"));
            task.rejectRemainingNewOrders();
            task.rejectRemainingNewOrders();
            verifyNoInteractions(resolver);
        }
    }
}

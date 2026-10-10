package com.trade.executor.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trade.executor.client.FauxnanceClient;
import com.trade.executor.domain.Quote;
import com.trade.executor.exception.NonRetryableOrderException;
import com.trade.executor.exception.RetryableOrderException;
import com.trade.executor.service.ExecutionService;
import com.trade.executor.service.OrderResolver;
import com.trade.executor.service.QuoteCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Consumes ORDER_PLACED from the orders topic and resolves the order.
 *
 * <p>The event only says which order to look at. The order row in Postgres is the
 * authority for everything else, so a replayed or stale event cannot fill an order
 * on numbers that are no longer true. An order that is no longer NEW is skipped:
 * that is the idempotency guarantee a replay relies on.
 *
 * <p>Failures are classified for the error handler: a malformed event can never
 * succeed and goes straight to orders.DLT; a database blip is retried with backoff.
 * The offset is acknowledged only after the order has been handled.
 */
@Component
public class OrderEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final ExecutionService executionService;
    private final OrderResolver orderResolver;
    private final QuoteCache quoteCache;
    private final FauxnanceClient fauxnanceClient;

    public OrderEventConsumer(ObjectMapper objectMapper,
                              ExecutionService executionService,
                              OrderResolver orderResolver,
                              QuoteCache quoteCache,
                              FauxnanceClient fauxnanceClient) {
        this.objectMapper = objectMapper;
        this.executionService = executionService;
        this.orderResolver = orderResolver;
        this.quoteCache = quoteCache;
        this.fauxnanceClient = fauxnanceClient;
    }

    @KafkaListener(topics = "${topics.orders}", groupId = "trade-executor",
                   containerFactory = "kafkaListenerContainerFactory")
    public void onOrderPlaced(String message, Acknowledgment ack) {
        JsonNode root = parse(message);
        if (!"ORDER_PLACED".equals(root.path("eventType").asText(""))) {
            ack.acknowledge();
            return;
        }
        Long orderId = readOrderId(root.path("payload"));

        try {
            Map<String, Object> order = executionService.fetchOrder(orderId);
            if (order == null) {
                // The API publishes after its commit, so the row should exist. Retry
                // in case this replica is behind; the dead-letter topic takes it after.
                throw new RetryableOrderException("Order " + orderId + " not found yet");
            }
            if (!"NEW".equalsIgnoreCase((String) order.get("status"))) {
                log.info("Order {} already {}, skipping", orderId, order.get("status"));
                ack.acknowledge();
                return;
            }

            // A limit order waits for its price: the market poller evaluates every NEW
            // limit order against each batch it polls and spends a fresh quote only when
            // the polled price says it can fill. Only a market order, which has no price
            // of its own to wait for, is executed on arrival against its own fresh quote.
            if (!"MARKET".equals(OrderResolver.orderType(order))) {
                log.info("Limit order {} queued for the market poller", orderId);
                ack.acknowledge();
                return;
            }

            OrderResolver.Outcome outcome = orderResolver.resolve(
                    OrderResolver.facts(order), OrderResolver.orderType(order), quoteFor((String) order.get("ticker")));
            log.info("Order {} {}", orderId, outcome);
            ack.acknowledge();
        } catch (TransientDataAccessException e) {
            throw new RetryableOrderException("Database unavailable resolving order " + orderId, e);
        }
    }

    /** Live quote first; the poller's last quote if the market-data API cannot be reached. */
    private Quote quoteFor(String symbol) {
        Quote quote = fauxnanceClient.fetchSingleQuote(symbol);
        if (quote == null) {
            quote = quoteCache.get(symbol);
            log.warn("No live quote for {}; using cached quote: {}", symbol, quote != null);
        }
        return quote;
    }

    private JsonNode parse(String message) {
        try {
            JsonNode root = objectMapper.readTree(message);
            // Tolerates events from older producers that double-encoded the JSON.
            return root.isTextual() ? objectMapper.readTree(root.asText()) : root;
        } catch (JsonProcessingException e) {
            throw new NonRetryableOrderException("Malformed JSON in order event: " + e.getOriginalMessage(), e);
        }
    }

    private static Long readOrderId(JsonNode payload) {
        String raw = payload.path("orderId").asText("").replaceFirst("^ORD-", "");
        try {
            long orderId = Long.parseLong(raw);
            if (orderId <= 0) {
                throw new NumberFormatException("not positive");
            }
            return orderId;
        } catch (NumberFormatException e) {
            throw new NonRetryableOrderException("ORDER_PLACED event without a valid orderId: '" + raw + "'");
        }
    }
}

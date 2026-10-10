package com.trading.tradeapi.strategy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.TestTokenFactory;
import com.trading.tradeapi.kafka.producer.OrderProducer;
import com.trading.tradeapi.kafka.producer.TradeEventProducer;
import com.trading.tradeapi.marketdata.QuoteEvent;
import com.trading.tradeapi.strategy.service.StrategyEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Automated strategies end to end on a real server: a strategy created over the API, a
 * quote from the price stream handed to the engine, and the resulting order placed over
 * real HTTP through {@code POST /api/v1/orders}, the Trade REST API, then found in the
 * orders table. Only the Kafka producers are replaced; there is no broker in tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties =
        "spring.datasource.url=jdbc:h2:mem:strategy-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@DisplayName("Automated strategy module")
class StrategyIntegrationTest {

    private static final String BASE = "/api/v1/strategies";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private StrategyEngine engine;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private com.trading.tradeapi.strategy.mapper.StrategyMapper strategyMapper;
    @Autowired
    private com.trading.tradeapi.marketdata.MarketDataService marketData;

    @MockitoBean
    private OrderProducer orderProducer;
    @MockitoBean
    private TradeEventProducer tradeEventProducer;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM strategy_execution");
        jdbc.update("DELETE FROM trading_strategy");
        jdbc.update("DELETE FROM orders WHERE idempotency_key LIKE 'strategy-%'");
        jdbc.update("DELETE FROM market_candles");
        jdbc.update("DELETE FROM market_quote");
        // Falling closes: a 3-day average under the 5-day one until the price jumps.
        String candles = "[{\"date\":\"2026-09-30\",\"close\":110},{\"date\":\"2026-10-01\",\"close\":108},"
                + "{\"date\":\"2026-10-02\",\"close\":106},{\"date\":\"2026-10-05\",\"close\":104},"
                + "{\"date\":\"2026-10-06\",\"close\":102},{\"date\":\"2026-10-07\",\"close\":100}]";
        jdbc.update("INSERT INTO market_candles (symbol, currency, candles, fetched_on) VALUES ('AAPL', 'USD', ?, CURRENT_DATE)",
                candles);
        jdbc.update("INSERT INTO market_quote (symbol, price, bid, ask, currency, market_state, stale, quote_as_of, fetched_at)"
                + " VALUES ('AAPL', 130, 129.9, 130.1, 'USD', 'open', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @Test
    @DisplayName("Another account's strategies are refused with ACC-403; no token is 401")
    void unauthorisedAccessRejected() throws Exception {
        mockMvc.perform(get(BASE + "/2").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
        mockMvc.perform(post(BASE + "/2").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json").content(crossoverBody()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(BASE + "/1")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Only instrument, rule, quantity and maximum order value are required; the rest defaults")
    void minimalStrategyGetsDefaults() throws Exception {
        mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"symbol":"aapl","strategyType":"BOLLINGER","quantity":2,"maxNotional":500}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("AAPL Bollinger 20/2"))
                .andExpect(jsonPath("$.bandPeriod").value(20))
                .andExpect(jsonPath("$.bandK").value(2))
                .andExpect(jsonPath("$.maxOrdersPerDay").value(5))
                .andExpect(jsonPath("$.maxPosition").value(10));
        mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"symbol":"AAPL","strategyType":"MA_CROSSOVER","quantity":1,"maxNotional":500}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("AAPL MA 20/50"))
                .andExpect(jsonPath("$.shortWindow").value(20))
                .andExpect(jsonPath("$.longWindow").value(50));
    }

    @Test
    @DisplayName("Price target: instrument, side, target and quantity are enough; it buys through the Trade API and completes")
    void priceTargetEndToEnd() throws Exception {
        String body = mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"symbol":"AAPL","strategyType":"PRICE_TARGET","side":"BUY","targetPrice":125,"quantity":2}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Buy 2 AAPL at or below 125"))
                .andExpect(jsonPath("$.orderType").value("MARKET"))
                .andExpect(jsonPath("$.maxNotional").value(300.0))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("strategyId").asLong();

        engine.onQuote(quote("evt-1", 130));
        assertThat(strategyOrders()).isEmpty();

        engine.onQuote(quote("evt-2", 124));
        assertThat(strategyOrders()).singleElement()
                .satisfies(o -> assertThat((String) o.get("IDEMPOTENCY_KEY")).startsWith("strategy-" + id + "-AAPL@"));
        assertThat(jdbc.queryForObject("SELECT status FROM trading_strategy WHERE strategy_id = ?", String.class, id))
                .isEqualTo("COMPLETED");

        // Completed: later quotes do nothing, and it no longer takes a slot.
        engine.onQuote(quote("evt-3", 120));
        assertThat(strategyOrders()).hasSize(1);
    }

    @Test
    @DisplayName("Creating the same price target twice (a double click) arms it once")
    void priceTargetCreationIsIdempotent() throws Exception {
        String body = """
                {"symbol":"AAPL","strategyType":"PRICE_TARGET","side":"BUY","targetPrice":120,"quantity":3}
                """;
        long first = objectMapper.readTree(mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                .contentType("application/json").content(body)).andReturn().getResponse().getContentAsString())
                .path("strategyId").asLong();
        long second = objectMapper.readTree(mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                .contentType("application/json").content(body)).andReturn().getResponse().getContentAsString())
                .path("strategyId").asLong();
        assertThat(second).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trading_strategy", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("The sweep trades a price target from the poller's stored quote, once, at market")
    void sweepUsesThePollersQuote() throws Exception {
        // Account 1 holds 100 AAPL; the stored quote is 130, so a sell at 125 or above is reached.
        String created = mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"symbol":"AAPL","strategyType":"PRICE_TARGET","side":"SELL","targetPrice":125,"quantity":4,
                                 "orderType":"LIMIT"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.orderType").value("MARKET"))
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(created).path("strategyId").asLong();

        com.trading.tradeapi.strategy.service.StrategyQuoteSweeper sweeper =
                new com.trading.tradeapi.strategy.service.StrategyQuoteSweeper(strategyMapper, marketData, engine);
        sweeper.sweep();
        sweeper.sweep(); // the same stored quote again: no second order

        assertThat(strategyOrders()).singleElement().satisfies(o -> {
            assertThat(o.get("SIDE")).isEqualTo("SELL");
            assertThat((String) o.get("IDEMPOTENCY_KEY")).startsWith("strategy-" + id + "-AAPL@");
        });
        assertThat(jdbc.queryForObject("SELECT status FROM trading_strategy WHERE strategy_id = ?", String.class, id))
                .isEqualTo("COMPLETED");
    }

    @Test
    @DisplayName("A price target without a side or target is refused")
    void priceTargetValidation() throws Exception {
        mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"symbol":"AAPL","strategyType":"PRICE_TARGET","quantity":2}
                                """))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @DisplayName("Invalid windows are refused")
    void invalidWindowsRefused() throws Exception {
        mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"name":"bad","symbol":"AAPL","strategyType":"MA_CROSSOVER",
                                 "shortWindow":10,"longWindow":5,"quantity":1,"maxNotional":1000,"maxPosition":500}
                                """))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    @Test
    @DisplayName("On a crossover the order is placed through the Trade API; with no crossover nothing is")
    void crossoverPlacesAnOrderThroughTheTradeApi() throws Exception {
        long id = create();

        // First quote: records where things stand (BELOW), trades nothing.
        assertThat(engine.onQuote(quote("evt-1", 99))).isEmpty();
        // Still below: condition not met, still nothing.
        assertThat(engine.onQuote(quote("evt-2", 98))).isEmpty();
        assertThat(strategyOrders()).isEmpty();

        // The jump lifts the 3-day average over the 5-day one: BUY.
        engine.onQuote(quote("evt-3", 130));

        List<Map<String, Object>> orders = strategyOrders();
        assertThat(orders).singleElement().satisfies(o -> {
            assertThat(o.get("ACCOUNT_ID")).isEqualTo(1L);
            assertThat(o.get("TICKER")).isEqualTo("AAPL");
            assertThat(o.get("SIDE")).isEqualTo("BUY");
            assertThat((String) o.get("IDEMPOTENCY_KEY")).startsWith("strategy-" + id + "-AAPL@");
        });

        JsonNode executions = objectMapper.readTree(mockMvc.perform(get(BASE + "/1/" + id + "/executions")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(executions).hasSize(1);
        assertThat(executions.get(0).path("status").asText()).isEqualTo("PLACED");
        assertThat(executions.get(0).path("orderId").asText()).isNotBlank();

        // The same event again (a Kafka redelivery) does not trade twice.
        engine.onQuote(quote("evt-3", 130));
        assertThat(strategyOrders()).hasSize(1);
    }

    @Test
    @DisplayName("A paused strategy does not trade")
    void pausedStrategyDoesNotTrade() throws Exception {
        long id = create();
        engine.onQuote(quote("evt-1", 99));
        mockMvc.perform(patch(BASE + "/1/" + id).header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json").content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        engine.onQuote(quote("evt-2", 130));
        assertThat(strategyOrders()).isEmpty();
    }

    @Test
    @DisplayName("Preview explains where the rule stands")
    void preview() throws Exception {
        long id = create();
        mockMvc.perform(get(BASE + "/1/" + id + "/preview").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ready").value(true))
                .andExpect(jsonPath("$.state").value("ABOVE"))
                .andExpect(jsonPath("$.shortAverage").isNumber());
    }

    @Test
    @DisplayName("Strategy-side limits do not block a triggered order")
    void strategyLimitsDoNotBlockTriggeredOrder() throws Exception {
        String body = """
                {"symbol":"AAPL","strategyType":"PRICE_TARGET","side":"BUY","targetPrice":125,"quantity":1,
                 "maxNotional":1,"maxOrdersPerDay":1,"maxPosition":1}
                """;
        long id = objectMapper.readTree(mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString())
                .path("strategyId").asLong();

        engine.onQuote(quote("evt-limits-off", 124));

        List<Map<String, Object>> orders = strategyOrders();
        assertThat(orders).singleElement().satisfies(o -> {
            assertThat(o.get("SIDE")).isEqualTo("BUY");
            assertThat((String) o.get("IDEMPOTENCY_KEY")).startsWith("strategy-" + id + "-AAPL@");
        });
        assertThat(jdbc.queryForObject("SELECT status FROM strategy_execution WHERE strategy_id = ?",
                String.class, id)).isEqualTo("PLACED");
    }

    @Test
    @DisplayName("Completed strategies cannot be resumed or deleted")
    void completedStrategiesAreImmutable() throws Exception {
        String body = mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json")
                        .content("""
                                {"symbol":"AAPL","strategyType":"PRICE_TARGET","side":"BUY","targetPrice":125,"quantity":2}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).path("strategyId").asLong();

        engine.onQuote(quote("evt-complete", 124));
        assertThat(jdbc.queryForObject("SELECT status FROM trading_strategy WHERE strategy_id = ?", String.class, id))
                .isEqualTo("COMPLETED");

        mockMvc.perform(patch(BASE + "/1/" + id).header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json").content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));

        mockMvc.perform(delete(BASE + "/1/" + id).header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));
    }

    private long create() throws Exception {
        return create(crossoverBody());
    }

    private long create(String requestBody) throws Exception {
        String body = mockMvc.perform(post(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L))
                        .contentType("application/json").content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("strategyId").asLong();
    }

    private static String crossoverBody() {
        return """
                {"name":"AAPL 3/5 cross","symbol":"AAPL","strategyType":"MA_CROSSOVER",
                 "shortWindow":3,"longWindow":5,"quantity":1,"maxNotional":1000,"maxOrdersPerDay":3,
                 "maxPosition":1000}
                """;
    }

    private static QuoteEvent quote(String eventId, double price) {
        return new QuoteEvent(eventId, "AAPL", BigDecimal.valueOf(price), "USD", false, Instant.now());
    }

    private List<Map<String, Object>> strategyOrders() {
        return jdbc.queryForList("SELECT account_id, ticker, side, idempotency_key FROM orders"
                + " WHERE idempotency_key LIKE 'strategy-%'");
    }

    private String tokenFor(long accountId) {
        return TestTokenFactory.createToken(accountId, jwtSecret, 60_000L);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Finding the order a strategy placed")
    class OrderLookups {

        @Test
        @DisplayName("By the exact idempotency key, and the latest for a strategy, without matching another strategy")
        void lookups() {
            jdbc.update("INSERT INTO orders (account_id, ticker, side, quantity, price, order_type, status, idempotency_key)"
                    + " VALUES (1, 'AAPL', 'SELL', 1, 100, 'MARKET', 'NEW', 'strategy-15-AAPL@t1')");
            jdbc.update("INSERT INTO orders (account_id, ticker, side, quantity, price, order_type, status, idempotency_key)"
                    + " VALUES (1, 'AAPL', 'SELL', 1, 100, 'MARKET', 'NEW', 'strategy-15-AAPL@t2#retry1')");
            Long latest = jdbc.queryForObject("SELECT MAX(order_id) FROM orders WHERE idempotency_key LIKE 'strategy-15-%'", Long.class);

            assertThat(strategyMapper.findOrderIdByIdempotencyKey("strategy-15-AAPL@t1")).startsWith("ORD-");
            assertThat(strategyMapper.findOrderIdByIdempotencyKey("strategy-15-AAPL@nope")).isNull();
            assertThat(strategyMapper.findLatestOrderIdForStrategy(15L)).isEqualTo("ORD-" + latest);
            assertThat(strategyMapper.findLatestOrderIdForStrategy(1L)).isNull();
        }
    }
}

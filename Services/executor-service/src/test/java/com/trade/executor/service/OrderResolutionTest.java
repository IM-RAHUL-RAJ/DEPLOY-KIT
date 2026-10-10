package com.trade.executor.service;

import com.trade.executor.domain.FillDecision;
import com.trade.executor.domain.FillRuleEvaluator;
import com.trade.executor.domain.Quote;
import com.trade.executor.exception.NonRetryableOrderException;
import com.trade.executor.exception.RetryableOrderException;
import com.trade.executor.kafka.TradeEventPublisher;
import com.trade.executor.kafka.TradeEventPublisher.OrderFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** How an order reaches its outcome: the resolver's decisions and the database writes behind them. */
@DisplayName("Order resolution")
class OrderResolutionTest {

    static Quote quote(String bid, String ask, String currency) {
        return new Quote("AAPL", new BigDecimal("100"), new BigDecimal(bid), new BigDecimal(ask), currency,
                null, null, null, "open", false, "2026-10-08T10:00:00Z");
    }

    static Map<String, Object> account(String status, String cash, String currency) {
        Map<String, Object> a = new HashMap<>();
        a.put("status", status);
        a.put("cash_balance", new BigDecimal(cash));
        a.put("currency", currency);
        a.put("version", 3L);
        return a;
    }

    @Nested
    @DisplayName("OrderResolver")
    class Resolver {

        private ExecutionService execution;
        private TradeEventPublisher publisher;
        private OrderResolver resolver;
        private final OrderFacts buy = new OrderFacts(7L, 1L, "AAPL", "BUY", 2L, new BigDecimal("102"));

        @BeforeEach
        void setUp() {
            execution = mock(ExecutionService.class);
            publisher = mock(TradeEventPublisher.class);
            resolver = new OrderResolver(execution, new FillRuleEvaluator(), new CurrencyConverter(), publisher);
            // No holding unless a test says otherwise (an unstubbed Map would be empty, not null).
            when(execution.fetchHolding(anyLong(), anyString())).thenReturn(null);
        }

        @Test
        @DisplayName("A fill is settled and published")
        void fills() {
            when(execution.fetchAccount(1L)).thenReturn(account("ACTIVE", "1000", "USD"));
            when(execution.settle(eq(7L), eq(1L), eq("AAPL"), eq("BUY"), eq(2L), any(), any())).thenReturn(true);

            assertThat(resolver.resolve(buy, "LIMIT", quote("99", "101", "USD"))).isEqualTo(OrderResolver.Outcome.FILLED);
            verify(publisher).publishFilled(eq(buy), any(FillDecision.class));
        }

        @Test
        @DisplayName("A fill that lost the race to another path is not published")
        void fillAlreadyResolved() {
            when(execution.fetchAccount(1L)).thenReturn(account("ACTIVE", "1000", null));
            when(execution.settle(anyLong(), anyLong(), anyString(), anyString(), anyLong(), any(), any())).thenReturn(false);

            assertThat(resolver.resolve(buy, "MARKET", quote("99", "101", "USD")))
                    .isEqualTo(OrderResolver.Outcome.ALREADY_RESOLVED);
            verify(publisher, never()).publishFilled(any(), any());
        }

        @Test
        @DisplayName("A rejection keeps the holding in the event, unless another path got there first")
        void rejects() {
            when(execution.fetchAccount(1L)).thenReturn(account("ACTIVE", "10", "USD"));
            when(execution.fetchHolding(1L, "AAPL")).thenReturn(Map.of("quantity", 4L, "avg_cost", new BigDecimal("90")));
            when(execution.reject(7L, "INSUFFICIENT_FUNDS")).thenReturn(true, false);

            assertThat(resolver.resolve(buy, "LIMIT", quote("99", "101", "USD"))).isEqualTo(OrderResolver.Outcome.REJECTED);
            verify(publisher).publishRejected(buy, "INSUFFICIENT_FUNDS", 4L, new BigDecimal("90"));
            assertThat(resolver.resolve(buy, "LIMIT", quote("99", "101", "USD")))
                    .isEqualTo(OrderResolver.Outcome.ALREADY_RESOLVED);
        }

        @Test
        @DisplayName("A limit order with no quote stays pending; an unknown account is never retried")
        void pendingAndUnknownAccount() {
            when(execution.fetchAccount(1L)).thenReturn(account("ACTIVE", "1000", "USD"));
            assertThat(resolver.resolve(buy, "LIMIT", null)).isEqualTo(OrderResolver.Outcome.PENDING);

            when(execution.fetchAccount(1L)).thenReturn(null);
            assertThatThrownBy(() -> resolver.resolve(buy, "LIMIT", null)).isInstanceOf(NonRetryableOrderException.class);
        }

        @Test
        @DisplayName("Expiry rejects and publishes once, with or without a holding")
        void expires() {
            when(execution.reject(7L, "MARKET_CLOSED")).thenReturn(true, true, false);
            when(execution.fetchHolding(1L, "AAPL"))
                    .thenReturn(null, Map.of("quantity", 3, "avg_cost", BigDecimal.TEN));

            assertThat(resolver.expire(buy, "MARKET_CLOSED")).isTrue();
            verify(publisher).publishRejected(buy, "MARKET_CLOSED", 0L, BigDecimal.ZERO);
            assertThat(resolver.expire(buy, "MARKET_CLOSED")).isTrue();
            verify(publisher).publishRejected(buy, "MARKET_CLOSED", 3L, BigDecimal.TEN);
            assertThat(resolver.expire(buy, "MARKET_CLOSED")).isFalse();
        }

        @Test
        @DisplayName("Rows become order facts, and a missing order type reads as a limit")
        void rowHelpers() {
            Map<String, Object> row = new HashMap<>(Map.of("order_id", 5, "account_id", 1, "ticker", "MSFT",
                    "side", "SELL", "quantity", 3, "price", BigDecimal.ONE));
            assertThat(OrderResolver.facts(row)).isEqualTo(new OrderFacts(5L, 1L, "MSFT", "SELL", 3L, BigDecimal.ONE));
            assertThat(OrderResolver.orderType(row)).isEqualTo("LIMIT");
            row.put("order_type", "  ");
            assertThat(OrderResolver.orderType(row)).isEqualTo("LIMIT");
            row.put("order_type", " market ");
            assertThat(OrderResolver.orderType(row)).isEqualTo("MARKET");
        }
    }

    @Nested
    @DisplayName("ExecutionService")
    class Execution {

        private JdbcTemplate jdbc;
        private ExecutionService service;
        private final FillDecision buyFill =
                FillDecision.filled(new BigDecimal("101"), new BigDecimal("-202"), 2L, new BigDecimal("101"));

        @BeforeEach
        void setUp() {
            jdbc = mock(JdbcTemplate.class);
            service = new ExecutionService(jdbc);
        }

        @Test
        @DisplayName("Reads answer the first row, or null when there is none")
        void reads() {
            Map<String, Object> row = Map.of("order_id", 7L);
            when(jdbc.queryForList(startsWith("SELECT order_id"), eq(7L))).thenReturn(List.of(row));
            when(jdbc.queryForList(startsWith("SELECT account_id"), eq(1L))).thenReturn(List.of());
            when(jdbc.queryForList(startsWith("SELECT quantity"), eq(1L), eq("AAPL"))).thenReturn(List.of(row));

            assertThat(service.fetchOrder(7L)).isEqualTo(row);
            assertThat(service.fetchAccount(1L)).isNull();
            assertThat(service.fetchHolding(1L, " aapl ")).isEqualTo(row);
            assertThat(service.fetchHolding(1L, null)).isNull();
        }

        @Test
        @DisplayName("A fill moves the order, the cash and the holding")
        void settles() {
            when(jdbc.update(startsWith("UPDATE orders"), any(), any(), eq(7L))).thenReturn(1);
            when(jdbc.update(startsWith("UPDATE account"), any(), eq(1L), eq(3L))).thenReturn(1);

            assertThat(service.settle(7L, 1L, "aapl", "BUY", 2L, buyFill, account("ACTIVE", "1000", "USD"))).isTrue();
            verify(jdbc).update(startsWith("UPDATE account"), eq(new BigDecimal("798")), eq(1L), eq(3L));
            verify(jdbc).update(startsWith("INSERT INTO holding"), eq(1L), eq("AAPL"), eq(2L), eq(new BigDecimal("101")));
        }

        @Test
        @DisplayName("Selling everything deletes the holding")
        void sellOutDeletesHolding() {
            when(jdbc.update(startsWith("UPDATE orders"), any(), any(), eq(7L))).thenReturn(1);
            when(jdbc.update(startsWith("UPDATE account"), any(), eq(1L), eq(3L))).thenReturn(1);
            FillDecision sellOut = FillDecision.filled(new BigDecimal("99"), new BigDecimal("198"), 0L, BigDecimal.ZERO);

            assertThat(service.settle(7L, 1L, "AAPL", "SELL", 2L, sellOut, account("ACTIVE", "0", "USD"))).isTrue();
            verify(jdbc).update(startsWith("DELETE FROM holding"), eq(1L), eq("AAPL"));
        }

        @Test
        @DisplayName("An order no longer NEW is skipped; an account changed underneath is retried")
        void guards() {
            when(jdbc.update(startsWith("UPDATE orders"), any(), any(), eq(7L))).thenReturn(0, 1);
            assertThat(service.settle(7L, 1L, "AAPL", "BUY", 2L, buyFill, account("ACTIVE", "1000", "USD"))).isFalse();

            when(jdbc.update(startsWith("UPDATE account"), any(), eq(1L), eq(3L))).thenReturn(0);
            assertThatThrownBy(() -> service.settle(7L, 1L, "AAPL", "BUY", 2L, buyFill, account("ACTIVE", "1000", "USD")))
                    .isInstanceOf(RetryableOrderException.class);
        }

        @Test
        @DisplayName("A rejection reports whether it changed the order")
        void rejects() {
            when(jdbc.update(startsWith("UPDATE orders SET status = 'REJECTED'"), eq("MARKET_CLOSED"), eq(7L)))
                    .thenReturn(1, 0);
            assertThat(service.reject(7L, "MARKET_CLOSED")).isTrue();
            assertThat(service.reject(7L, "MARKET_CLOSED")).isFalse();
        }
    }

    @Nested
    @DisplayName("QuoteCache")
    class Cache {

        @Test
        @DisplayName("Stores by upper-case symbol and answers null for an unknown or missing one")
        void caches() {
            QuoteCache cache = new QuoteCache();
            cache.put("aapl", quote("1", "2", "USD"));
            assertThat(cache.get("AAPL")).isNotNull();
            assertThat(cache.get("MSFT")).isNull();
            assertThat(cache.get(null)).isNull();
            assertThat(cache.size()).isEqualTo(1);
        }
    }
}

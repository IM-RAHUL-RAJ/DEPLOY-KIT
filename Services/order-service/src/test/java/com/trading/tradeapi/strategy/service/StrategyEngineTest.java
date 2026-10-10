package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.marketdata.CandleService;
import com.trading.tradeapi.marketdata.QuoteEvent;
import com.trading.tradeapi.portfolio.api.PortfolioPositions;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.strategy.entity.StrategyExecutionRecord;
import com.trading.tradeapi.strategy.entity.StrategyRecord;
import com.trading.tradeapi.strategy.mapper.StrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("Strategy engine")
class StrategyEngineTest {
    private static final Instant NOW = Instant.parse("2026-10-08T14:00:00Z");

    private StrategyMapper mapper;
    private CandleService candles;
    private PortfolioPositions portfolio;
    private StrategyOrderPlacer placer;
    private StrategyEngine engine;
    private StrategyRecord strategy;

    @BeforeEach
    void setUp() {
        mapper = mock(StrategyMapper.class);
        candles = mock(CandleService.class);
        portfolio = mock(PortfolioPositions.class);
        placer = mock(StrategyOrderPlacer.class);
        engine = new StrategyEngine(mapper, candles, portfolio, placer, Clock.fixed(NOW, ZoneOffset.UTC));

        strategy = StrategyRulesTest.crossover(3, 5);
        strategy.setStrategyId(7L);
        strategy.setAccountId(1L);
        strategy.setSymbol("AAPL");
        strategy.setQuantity(2L);
        strategy.setMaxNotional(new BigDecimal("1000"));
        strategy.setMaxOrdersPerDay(5);
        strategy.setMaxPosition(100L);
        strategy.setStatus("ACTIVE");
        strategy.setConsecutiveFailures(0);
        strategy.setLastState("BELOW");

        when(mapper.findActiveBySymbol("AAPL")).thenReturn(List.of(strategy));
        when(mapper.findById(7L)).thenReturn(strategy);
        when(mapper.insertExecution(any())).thenReturn(1);
        when(candles.storedDailyCloses(List.of("AAPL"))).thenReturn(Map.of("AAPL", StrategyRulesTest.FALLING));
        when(placer.place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                .thenReturn(new StrategyOrderPlacer.PlacedOrder("ORD-1", "NEW"));
    }

    private static QuoteEvent quote(double price) {
        return new QuoteEvent("evt-1", "AAPL", BigDecimal.valueOf(price), "USD", false, NOW);
    }

    private StrategyRecord priceTarget(String side, double target, String orderType) {
        StrategyRecord t = new StrategyRecord();
        t.setStrategyId(9L);
        t.setAccountId(1L);
        t.setSymbol("AAPL");
        t.setStrategyType(StrategyRules.PRICE_TARGET);
        t.setSide(side);
        t.setTargetPrice(BigDecimal.valueOf(target));
        t.setOrderType(orderType);
        t.setQuantity(2L);
        t.setMaxNotional(new BigDecimal("1000"));
        t.setMaxOrdersPerDay(5);
        t.setMaxPosition(100L);
        t.setStatus("ACTIVE");
        t.setConsecutiveFailures(0);
        when(mapper.findActiveBySymbol("AAPL")).thenReturn(List.of(t));
        when(mapper.findById(9L)).thenReturn(t);
        return t;
    }

    @Nested
    @DisplayName("Indicator rules")
    class IndicatorRules {

        @Test
        @DisplayName("A crossover places a MARKET BUY through the Trade API and records it PLACED")
        void crossoverTriggersAnOrder() {
            List<StrategyExecutionRecord> decisions = engine.onQuote(quote(130));

            verify(placer).place(7L, 1L, "AAPL", "BUY", 2L, "MARKET", null, "strategy-7-AAPL@2026-10-08T14:00:00Z");
            verify(mapper).recordEvaluation(7L, "ABOVE", BigDecimal.valueOf(130.0), NOW);
            ArgumentCaptor<StrategyExecutionRecord> row = ArgumentCaptor.forClass(StrategyExecutionRecord.class);
            verify(mapper).insertExecution(row.capture());
            assertThat(row.getValue().getSignal()).isEqualTo("BUY");
            verify(mapper).completeExecution(any(), eq("PLACED"), eq("ORD-1"), eq(null));
            assertThat(decisions).singleElement().satisfies(d -> {
                assertThat(d.getStatus()).isEqualTo("PLACED");
                assertThat(d.getOrderId()).isEqualTo("ORD-1");
            });
        }

        @Test
        @DisplayName("No action when the condition is not met: the averages have not crossed")
        void noActionWithoutACrossover() {
            List<StrategyExecutionRecord> decisions = engine.onQuote(quote(99));

            assertThat(decisions).isEmpty();
            verify(mapper).recordEvaluation(7L, "BELOW", BigDecimal.valueOf(99.0), NOW);
            verify(mapper, never()).insertExecution(any());
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("No action on the first reading of a new strategy, even on the far side")
        void firstReadingOnlyRecordsState() {
            strategy.setLastState(null);

            assertThat(engine.onQuote(quote(130))).isEmpty();
            verify(mapper).recordEvaluation(7L, "ABOVE", BigDecimal.valueOf(130.0), NOW);
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("A stale quote is never traded on")
        void staleQuoteIgnored() {
            // The crossover would fire at 130; on a stale quote an indicator rule is not even evaluated.
            assertThat(engine.onQuote(new QuoteEvent("evt-1", "AAPL", BigDecimal.valueOf(130), "USD", true, NOW))).isEmpty();
            verify(mapper, never()).recordEvaluation(anyLong(), any(), any(), any());
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("Limits")
    class Limits {

        @Test
        @DisplayName("Order value, daily count and position size no longer hold an order back: only holdings do")
        void limitsNoLongerBlock() {
            strategy.setQuantity(10L); // 10 x 130 = 1300, over the stored maximum value of 1000
            when(mapper.countPlacedSince(eq(7L), any())).thenReturn(5);
            when(portfolio.pricedPositions(1L)).thenReturn(List.of(new PricedPositionDto(1L, "AAPL", 99, null, null,
                    null, null, null, null, "USD", null, false)));

            assertThat(engine.onQuote(quote(130))).singleElement()
                    .satisfies(d -> assertThat(d.getStatus()).isEqualTo("PLACED"));
            verify(placer).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("A SELL without the shares is skipped")
        void sellWithoutHoldingSkipped() {
            strategy.setLastState("ABOVE");
            when(candles.storedDailyCloses(List.of("AAPL")))
                    .thenReturn(Map.of("AAPL", List.of(100.0, 102.0, 104.0, 106.0, 108.0, 110.0)));
            when(portfolio.pricedPositions(1L)).thenReturn(List.of());

            assertThat(engine.onQuote(quote(80))).singleElement().satisfies(d -> {
                assertThat(d.getSignal()).isEqualTo("SELL");
                assertThat(d.getStatus()).isEqualTo("SKIPPED");
            });
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("A SELL with the shares is placed")
        void sellWithHoldingPlaced() {
            strategy.setLastState("ABOVE");
            when(candles.storedDailyCloses(List.of("AAPL")))
                    .thenReturn(Map.of("AAPL", List.of(100.0, 102.0, 104.0, 106.0, 108.0, 110.0)));
            when(portfolio.pricedPositions(1L)).thenReturn(List.of(new PricedPositionDto(1L, "AAPL", 5, null, null,
                    null, null, null, null, "USD", null, false)));

            engine.onQuote(quote(80));
            verify(placer).place(7L, 1L, "AAPL", "SELL", 2L, "MARKET", null, "strategy-7-AAPL@2026-10-08T14:00:00Z");
        }

    }

    @Nested
    @DisplayName("Price targets")
    class PriceTargets {

        @Test
        @DisplayName("A price target places a limit order at the target once reached, then completes")
        void priceTargetPlacesAndCompletes() {
            priceTarget("BUY", 180, "LIMIT");

            assertThat(engine.onQuote(quote(185))).isEmpty();
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
            verify(candles, never()).storedDailyCloses(any());

            engine.onQuote(quote(179.5));
            verify(placer).place(9L, 1L, "AAPL", "BUY", 2L, "LIMIT", BigDecimal.valueOf(180.0), "strategy-9-AAPL@2026-10-08T14:00:00Z");
            verify(mapper).complete(eq(9L), org.mockito.ArgumentMatchers.contains("ORD-1"));
        }

        @Test
        @DisplayName("A market price target sells at market once the price reaches it")
        void marketPriceTarget() {
            priceTarget("SELL", 150, "MARKET");
            when(portfolio.pricedPositions(1L)).thenReturn(List.of(new PricedPositionDto(1L, "AAPL", 5, null, null,
                    null, null, null, null, "USD", null, false)));

            engine.onQuote(quote(151));
            verify(placer).place(9L, 1L, "AAPL", "SELL", 2L, "MARKET", null, "strategy-9-AAPL@2026-10-08T14:00:00Z");
        }

        @Test
        @DisplayName("A price target still fires on a stale quote: the market order is priced fresh at execution")
        void priceTargetFiresOnAStaleQuote() {
            priceTarget("BUY", 180, "MARKET");

            engine.onQuote(new QuoteEvent("evt-1", "AAPL", BigDecimal.valueOf(170), "USD", true, NOW));

            verify(placer).place(9L, 1L, "AAPL", "BUY", 2L, "MARKET", null, "strategy-9-AAPL@2026-10-08T14:00:00Z");
        }

        @Test
        @DisplayName("A price target that a limit refuses stops instead of refusing on every quote")
        void refusedPriceTargetStops() {
            priceTarget("SELL", 150, "MARKET");
            when(portfolio.pricedPositions(1L)).thenReturn(List.of());

            assertThat(engine.onQuote(quote(151))).singleElement()
                    .satisfies(d -> assertThat(d.getStatus()).isEqualTo("SKIPPED"));
            verify(mapper).stop(eq(9L), org.mockito.ArgumentMatchers.contains("Target reached but not traded"));
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("When the order call fails or times out")
    class CallFailures {

        @Test
        @DisplayName("A call that timed out after the order went in is PLACED with that order, not FAILED")
        void timeoutButOrderLanded() {
            priceTarget("SELL", 150, "MARKET");
            when(portfolio.pricedPositions(1L)).thenReturn(List.of(new PricedPositionDto(1L, "AAPL", 5, null, null,
                    null, null, null, null, "USD", null, false)));
            when(placer.place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                    .thenThrow(new StrategyOrderPlacer.OrderPlacementException("Trade API unreachable: Read timed out"));
            when(mapper.findOrderIdByIdempotencyKey("strategy-9-AAPL@2026-10-08T14:00:00Z")).thenReturn("ORD-68");

            assertThat(engine.onQuote(quote(151))).singleElement().satisfies(d -> {
                assertThat(d.getStatus()).isEqualTo("PLACED");
                assertThat(d.getOrderId()).isEqualTo("ORD-68");
            });
            verify(mapper).complete(eq(9L), org.mockito.ArgumentMatchers.contains("ORD-68"));
            verify(mapper, never()).incrementFailures(anyLong());
        }

        @Test
        @DisplayName("A target that already placed an order completes with it instead of trading again")
        void earlierOrderCompletesTheTarget() {
            priceTarget("BUY", 180, "MARKET");
            when(mapper.findLatestOrderIdForStrategy(9L)).thenReturn("ORD-50");

            assertThat(engine.onQuote(quote(170))).singleElement()
                    .satisfies(d -> assertThat(d.getOrderId()).isEqualTo("ORD-50"));
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
            verify(mapper).complete(eq(9L), org.mockito.ArgumentMatchers.contains("ORD-50"));
        }

        @Test
        @DisplayName("After a failed order the next attempt on the same quote uses a new decision key")
        void retryOnTheSameQuote() {
            StrategyRecord t = priceTarget("BUY", 180, "MARKET");
            t.setConsecutiveFailures(1);

            engine.onQuote(quote(170));

            verify(placer).place(9L, 1L, "AAPL", "BUY", 2L, "MARKET", null, "strategy-9-AAPL@2026-10-08T14:00:00Z#retry1");
            assertThat(StrategyEngine.eventIdFor(t, "X@1")).isEqualTo("X@1#retry1");
            t.setConsecutiveFailures(null);
            assertThat(StrategyEngine.eventIdFor(t, "X@1")).isEqualTo("X@1");
        }
    }

    @Nested
    @DisplayName("Pausing, redelivery and failures")
    class PausingRedeliveryAndFailures {

        @Test
        @DisplayName("Paused between the decision and the claim: the conditional insert writes nothing and no order is placed")
        void pausedAtTheClaimDoesNotTrade() {
            when(mapper.insertExecution(any())).thenReturn(0);

            assertThat(engine.onQuote(quote(130))).isEmpty();
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("A redelivered quote does not trade twice")
        void redeliveryIgnored() {
            doThrow(new DuplicateKeyException("dup")).when(mapper).insertExecution(any());

            assertThat(engine.onQuote(quote(130))).isEmpty();
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("A strategy paused a moment ago does not trade")
        void pausedStrategyDoesNotTrade() {
            StrategyRecord paused = StrategyRulesTest.crossover(3, 5);
            paused.setStatus("DISABLED");
            when(mapper.findById(7L)).thenReturn(paused);

            assertThat(engine.onQuote(quote(130))).isEmpty();
            verify(placer, never()).place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("A failed order is recorded; the third in a row stops the strategy")
        void failuresStopTheStrategy() {
            when(placer.place(anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString(), any(), anyString()))
                    .thenThrow(new StrategyOrderPlacer.OrderPlacementException("Trade API 422: Insufficient funds"));

            strategy.setConsecutiveFailures(0);
            assertThat(engine.onQuote(quote(130))).singleElement()
                    .satisfies(d -> assertThat(d.getStatus()).isEqualTo("FAILED"));
            verify(mapper).incrementFailures(7L);
            verify(mapper, never()).stop(anyLong(), anyString());

            strategy.setConsecutiveFailures(2);
            engine.onQuote(quote(130));
            verify(mapper).stop(eq(7L), org.mockito.ArgumentMatchers.contains("Insufficient funds"));
        }
    }
}

package com.trading.tradeapi.strategy.service;

import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.marketdata.CandleService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.portfolio.api.PortfolioPositions;
import com.trading.tradeapi.strategy.dto.CreateStrategyRequest;
import com.trading.tradeapi.strategy.dto.StrategyPreview;
import com.trading.tradeapi.strategy.entity.StrategyRecord;
import com.trading.tradeapi.strategy.mapper.StrategyMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** StrategyService without a database: creation rules, ownership, and the preview's explanations. */
@DisplayName("StrategyService")
class StrategyServiceUnitTest {

    private StrategyMapper mapper;
    private InstrumentMapper instruments;
    private CandleService candles;
    private MarketDataService marketData;
    private PortfolioPositions portfolio;
    private StrategyQuoteSweeper sweeper;
    private StrategyService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        mapper = mock(StrategyMapper.class);
        instruments = mock(InstrumentMapper.class);
        candles = mock(CandleService.class);
        marketData = mock(MarketDataService.class);
        portfolio = mock(PortfolioPositions.class);
        sweeper = mock(StrategyQuoteSweeper.class);
        ObjectProvider<StrategyQuoteSweeper> provider = mock(ObjectProvider.class);
        doAnswer(inv -> {
            ((Consumer<StrategyQuoteSweeper>) inv.getArgument(0)).accept(sweeper);
            return null;
        }).when(provider).ifAvailable(any());
        service = new StrategyService(mapper, instruments, candles, marketData, portfolio, provider);
        when(instruments.findBySymbol("AAPL")).thenReturn(new InstrumentRecord(1L, "AAPL", "AAPL", "EQUITY", "USD", "ACTIVE", null));
    }

    private static CreateStrategyRequest request(String type, String side, String target, Long qty, String maxNotional,
                                                 Long maxPosition, Integer bandPeriod, String bandK) {
        return new CreateStrategyRequest(null, "aapl", type, side, target == null ? null : new BigDecimal(target), null,
                null, null, bandPeriod, bandK == null ? null : new BigDecimal(bandK), qty,
                maxNotional == null ? null : new BigDecimal(maxNotional), null, maxPosition);
    }

    private static StrategyRecord strategy(String type) {
        StrategyRecord s = new StrategyRecord();
        s.setStrategyId(7L);
        s.setAccountId(1L);
        s.setSymbol("AAPL");
        s.setStrategyType(type);
        s.setQuantity(2L);
        s.setShortWindow(3);
        s.setLongWindow(5);
        s.setBandPeriod(5);
        s.setBandK(new BigDecimal("2"));
        s.setMaxOrdersPerDay(5);
        return s;
    }

    private static MarketQuote quote(String price) {
        return new MarketQuote("AAPL", new BigDecimal(price), null, null, "USD", null, null, "open", false,
                Instant.now(), Instant.now());
    }

    @Nested
    @DisplayName("Creating")
    class Creating {

        @Test
        @DisplayName("Refuses an untradable instrument, a full account, a target without side, and a rule without a maximum value")
        void refusals() {
            when(instruments.findBySymbol("ZZZ")).thenReturn(null);
            CreateStrategyRequest unknown = new CreateStrategyRequest(null, "zzz", "PRICE_TARGET", "BUY", BigDecimal.ONE,
                    null, null, null, null, null, 1L, null, null, null);
            assertThatThrownBy(() -> service.create(1L, unknown)).isInstanceOf(DomainException.class).hasMessageContaining("not tradable");

            when(mapper.countByAccount(1L)).thenReturn(10, 0);
            assertThatThrownBy(() -> service.create(1L, request("PRICE_TARGET", "BUY", "100", 1L, null, null, null, null)))
                    .hasMessageContaining("at most");
            assertThatThrownBy(() -> service.create(1L, request("PRICE_TARGET", null, "100", 1L, null, null, null, null)))
                    .hasMessageContaining("needs a side");
            when(mapper.countByAccount(1L)).thenReturn(0);
            assertThatThrownBy(() -> service.create(1L, request("PRICE_TARGET", "BUY", null, 1L, null, null, null, null)))
                    .hasMessageContaining("needs a side");
            assertThatThrownBy(() -> service.create(1L, request("BOLLINGER", null, null, 5L, "1000", 2L, null, null)))
                    .hasMessageContaining("maxPosition");
        }

        @Test
        @DisplayName("A rule without a maximum order value is stored uncapped")
        void noMaxNotionalIsUncapped() {
            when(mapper.findById(any())).thenReturn(strategy("BOLLINGER"));
            org.mockito.ArgumentCaptor<StrategyRecord> stored = org.mockito.ArgumentCaptor.forClass(StrategyRecord.class);
            service.create(1L, request("BOLLINGER", null, null, 1L, null, null, null, null));
            verify(mapper).insert(stored.capture());
            assertThat(stored.getValue().getMaxNotional()).isEqualByComparingTo(StrategyService.DEFAULT_UNBOUNDED_MAX_NOTIONAL);
        }

        @Test
        @DisplayName("A Bollinger rule takes the standard bands unless told otherwise, and asks for its history")
        void bollingerDefaults() {
            when(mapper.findById(any())).thenReturn(strategy("BOLLINGER"));
            service.create(1L, request("BOLLINGER", null, null, 1L, "1000", null, null, null));
            service.create(1L, request("BOLLINGER", null, null, 1L, "1000", 3L, 30, "2.5"));
            verify(mapper, org.mockito.Mockito.times(2)).insert(any());
            verify(candles, org.mockito.Mockito.times(2)).storedDailyCloses(List.of("AAPL"));
        }

        @Test
        @DisplayName("A price target is checked at once, and a failed check does not undo it")
        void targetCheckedAtOnce() {
            when(mapper.findById(any())).thenReturn(strategy("PRICE_TARGET"));
            when(portfolio.pricedPositions(1L)).thenReturn(List.of());
            doAnswer(inv -> {
                throw new IllegalStateException("no quote yet");
            }).when(sweeper).evaluate("AAPL");

            assertThat(service.create(1L, request("PRICE_TARGET", "SELL", "250", 1L, null, null, null, null))).isNotNull();
            verify(sweeper).evaluate("AAPL");
        }
    }

    @Nested
    @DisplayName("Owning")
    class Owning {

        @Test
        @DisplayName("Lists, deletes and reads only the account's own strategies")
        void ownership() {
            when(mapper.findByAccount(1L)).thenReturn(List.of(strategy("BOLLINGER")));
            assertThat(service.list(1L)).hasSize(1);

            when(mapper.findByIdAndAccount(7L, 1L)).thenReturn(strategy("BOLLINGER"));
            service.delete(1L, 7L);
            verify(mapper).delete(7L, 1L);

            assertThatThrownBy(() -> service.delete(2L, 7L)).hasMessageContaining("not found");

            // A completed target keeps its history: it can be neither resumed nor deleted.
            StrategyRecord done = strategy("PRICE_TARGET");
            done.setStatus("COMPLETED");
            when(mapper.findByIdAndAccount(8L, 1L)).thenReturn(done);
            assertThatThrownBy(() -> service.setStatus(1L, 8L, "ACTIVE")).hasMessageContaining("cannot be resumed");
            assertThatThrownBy(() -> service.delete(1L, 8L)).hasMessageContaining("cannot be deleted");

            when(mapper.findById(7L)).thenReturn(strategy("BOLLINGER"));
            assertThat(service.setStatus(1L, 7L, " disabled ")).isNotNull();
            verify(mapper).updateStatus(7L, 1L, "DISABLED");
            assertThatThrownBy(() -> service.preview(2L, 7L)).hasMessageContaining("not found");
        }
    }

    @Nested
    @DisplayName("Preview")
    class Preview {

        private StrategyPreview preview(StrategyRecord s, List<Double> closes, MarketQuote quote) {
            when(mapper.findByIdAndAccount(anyLong(), anyLong())).thenReturn(s);
            when(candles.dailyCloses(any())).thenReturn(closes == null ? Map.of() : Map.of("AAPL", closes));
            when(marketData.quotes(any())).thenReturn(quote == null ? Map.of() : Map.of("AAPL", quote));
            return service.preview(1L, 7L);
        }

        @Test
        @DisplayName("A price target explains how far the price is from it, and says when it is reached")
        void priceTarget() {
            StrategyRecord buy = strategy("PRICE_TARGET");
            buy.setSide("BUY");
            buy.setTargetPrice(new BigDecimal("100"));
            buy.setOrderType("MARKET");
            assertThat(preview(buy, null, quote("110")).explanation()).contains("Buys 2 at market").contains("above the target");

            StrategyRecord sell = strategy("PRICE_TARGET");
            sell.setSide("SELL");
            sell.setTargetPrice(new BigDecimal("100"));
            sell.setOrderType("LIMIT");
            assertThat(preview(sell, null, quote("90")).explanation()).contains("a limit of the target").contains("below the target");
            assertThat(preview(sell, null, quote("120")).explanation()).contains("target reached");

            StrategyRecord noTarget = strategy("PRICE_TARGET");
            assertThat(preview(noTarget, null, quote("120")).ready()).isFalse();
        }

        @Test
        @DisplayName("Bollinger reads below, above or inside the bands; too little history waits")
        void bollinger() {
            List<Double> flat = List.of(100.0, 101.0, 99.0, 100.0);
            // One-sigma bands: a single outlier among five points cannot break two-sigma ones.
            StrategyRecord narrow = strategy("BOLLINGER");
            narrow.setBandK(BigDecimal.ONE);
            assertThat(preview(narrow, flat, quote("150")).explanation()).contains("above the upper band");
            assertThat(preview(narrow, flat, quote("50")).explanation()).contains("below the lower band");
            StrategyPreview inside = preview(strategy("BOLLINGER"), flat, quote("100"));
            assertThat(inside.explanation()).contains("inside the bands");
            assertThat(inside.lowerBand()).isNotNull();
            assertThat(preview(strategy("BOLLINGER"), List.of(100.0), quote("100")).explanation()).contains("Needs 4 daily closes");
        }

        @Test
        @DisplayName("A crossover reports both averages, using the last close when there is no live quote")
        void crossover() {
            List<Double> rising = IntStream.range(0, 6).mapToObj(i -> 100.0 + i).toList();
            StrategyPreview p = preview(strategy("MA_CROSSOVER"), rising, null);
            assertThat(p.shortAverage()).isNotNull();
            assertThat(p.explanation()).contains("is above");

            List<Double> falling = IntStream.range(0, 6).mapToObj(i -> 100.0 - i).toList();
            assertThat(preview(strategy("MA_CROSSOVER"), falling, null).explanation()).contains("is below");
        }

        @Test
        @DisplayName("No price at all waits for data")
        void noPrice() {
            assertThat(preview(strategy("MA_CROSSOVER"), null, null).explanation()).contains("Waiting for price data");
        }
    }
}

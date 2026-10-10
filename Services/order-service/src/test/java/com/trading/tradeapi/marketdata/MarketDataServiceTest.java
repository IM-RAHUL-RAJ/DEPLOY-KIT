package com.trading.tradeapi.marketdata;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.MarketDataUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static com.trading.tradeapi.marketdata.TestQuotes.q;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The order service's only route to prices: it reads what the market-data poller
 * stored, and asks the poller (through market_interest) for what it has not got.
 */
class MarketDataServiceTest {

    private MarketDataMapper mapper;
    private MarketInterestRegistry registry;
    private MarketDataService service;

    @BeforeEach
    void setUp() {
        mapper = mock(MarketDataMapper.class);
        registry = mock(MarketInterestRegistry.class);
        service = new MarketDataService(mapper, registry);
        service.awaitMs = 400;
        service.stepMs = 20;
    }

    private static MarketCandles candles(LocalDate fetchedOn) {
        return new MarketCandles("AAPL", "USD",
                "[{\"date\":\"2026-10-05\",\"open\":1,\"high\":2,\"low\":0.5,\"close\":1.5,\"volume\":10},"
                        + "{\"date\":\"2026-10-06\",\"open\":1.5,\"high\":2.5,\"low\":1,\"close\":2,\"volume\":null}]",
                fetchedOn, Instant.now());
    }

    @Nested
    @DisplayName("one quote")
    class OneQuote {

        @Test
        @DisplayName("a quote the poller already has is returned at once, and the symbol is marked as viewed")
        void knownQuoteIsImmediate() {
            when(mapper.findQuote("AAPL")).thenReturn(q("AAPL", new BigDecimal("150"), "USD", false));

            MarketDataService.QuoteLookup lookup = service.quote(" aapl ");

            assertThat(lookup.found()).isTrue();
            assertThat(lookup.quote().price()).isEqualByComparingTo("150");
            verify(registry).register("AAPL", "VIEWED", 30);
            verify(mapper, never()).isNotFound(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("a new symbol is requested from the poller and returned once it arrives")
        void newSymbolArrives() {
            when(mapper.findQuote("TCS.NS")).thenReturn(null, null, q("TCS.NS", new BigDecimal("4000"), "INR", false));

            MarketDataService.QuoteLookup lookup = service.quote("TCS.NS");

            assertThat(lookup.found()).isTrue();
            assertThat(lookup.quote().currency()).isEqualTo("INR");
            verify(registry).register("TCS.NS", "VIEWED", 30);
        }

        @Test
        @DisplayName("a symbol Fauxnance does not know is answered as not found without waiting out the window")
        void unknownSymbol() {
            when(mapper.findQuote("NOPE")).thenReturn(null);
            when(mapper.isNotFound("NOPE", "VIEWED", 30)).thenReturn(true);

            long start = System.currentTimeMillis();
            MarketDataService.QuoteLookup lookup = service.quote("NOPE");

            assertThat(lookup.found()).isFalse();
            assertThat(lookup.notFound()).isTrue();
            assertThat(System.currentTimeMillis() - start).isLessThan(service.awaitMs);
        }

        @Test
        @DisplayName("a symbol the poller has not answered in time is absent but not called unknown")
        void slowPoller() {
            when(mapper.findQuote("SLOW")).thenReturn(null);

            MarketDataService.QuoteLookup lookup = service.quote("SLOW");

            assertThat(lookup.found()).isFalse();
            assertThat(lookup.notFound()).isFalse();
        }

        @Test
        @DisplayName("a blank symbol is not found and registers nothing")
        void blank() {
            assertThat(service.quote("  ").notFound()).isTrue();
            verify(registry, never()).register(anyString(), anyString(), anyInt());
        }
    }

    @Nested
    @DisplayName("several quotes")
    class SeveralQuotes {

        @Test
        @DisplayName("stored quotes are returned keyed by upper-case symbol, with no request when all are stored")
        void allStored() {
            when(mapper.findQuotes(anyCollection())).thenReturn(List.of(
                    q("AAPL", new BigDecimal("150"), "USD", false),
                    q("MSFT", new BigDecimal("400"), "USD", false)));

            Map<String, MarketQuote> quotes = service.quotes(List.of("aapl", "MSFT"));

            assertThat(quotes).containsOnlyKeys("AAPL", "MSFT");
            verify(registry, never()).register(anyString(), anyString(), anyInt());
        }

        @Test
        @DisplayName("missing symbols are requested together and filled in as they arrive")
        void missingAreRequested() {
            when(mapper.findQuotes(anyCollection())).thenReturn(
                    List.of(q("AAPL", new BigDecimal("150"), "USD", false)),
                    List.of(q("INFY.NS", new BigDecimal("1500"), "INR", false)));

            Map<String, MarketQuote> quotes = service.quotes(List.of("AAPL", "INFY.NS"));

            assertThat(quotes).containsOnlyKeys("AAPL", "INFY.NS");
            verify(registry).register("INFY.NS", "VIEWED", 30);
        }

        @Test
        @DisplayName("an unknown symbol stops being waited for and is simply absent")
        void unknownIsAbsent() {
            when(mapper.findQuotes(anyCollection())).thenReturn(List.of(q("AAPL", new BigDecimal("150"), "USD", false)), List.of());
            when(mapper.isNotFound("NOPE", "VIEWED", 30)).thenReturn(true);

            assertThat(service.quotes(List.of("AAPL", "NOPE"))).containsOnlyKeys("AAPL");
        }
    }

    @Nested
    @DisplayName("candles")
    class Candles {

        @Test
        @DisplayName("candles fetched today are served without waiting")
        void todaysCandles() {
            when(mapper.findCandles("AAPL")).thenReturn(candles(LocalDate.now()));

            MarketDataService.CandlesLookup lookup = service.candles("AAPL");

            assertThat(lookup.candles()).isNotNull();
            verify(registry).register("AAPL", "CANDLES", 24 * 60);
        }

        @Test
        @DisplayName("yesterday's candles are served when today's do not arrive in time")
        void olderFallback() {
            when(mapper.findCandles("AAPL")).thenReturn(candles(LocalDate.now().minusDays(1)));

            assertThat(service.candles("AAPL").candles().fetchedOn()).isEqualTo(LocalDate.now().minusDays(1));
        }

        @Test
        @DisplayName("an unknown symbol with no candles at all is not found")
        void unknownCandles() {
            when(mapper.findCandles("NOPE")).thenReturn(null);
            when(mapper.isNotFound("NOPE", "CANDLES", 30)).thenReturn(true);

            MarketDataService.CandlesLookup lookup = service.candles("NOPE");

            assertThat(lookup.candles()).isNull();
            assertThat(lookup.notFound()).isTrue();
        }
    }

    @Nested
    @DisplayName("candle response")
    class CandleResponse {

        private CandleService candleService() {
            MarketDataService marketData = mock(MarketDataService.class);
            when(marketData.candles("AAPL")).thenReturn(new MarketDataService.CandlesLookup(candles(LocalDate.now()), false));
            when(marketData.candles("NOPE")).thenReturn(new MarketDataService.CandlesLookup(null, true));
            when(marketData.candles("SLOW")).thenReturn(new MarketDataService.CandlesLookup(null, false));
            return new CandleService(marketData, new ObjectMapper());
        }

        @Test
        @DisplayName("Fauxnance candles become the chart's points: Unix seconds and o/h/l/c/v")
        void mapsForTheChart() {
            CandleService.CandlesResponse response = candleService().candles("AAPL", null, null);

            assertThat(response.interval()).isEqualTo("1d");
            assertThat(response.currency()).isEqualTo("USD");
            assertThat(response.candles()).hasSize(2);
            CandleService.Candle first = response.candles().get(0);
            assertThat(first.t()).isEqualTo(LocalDate.parse("2026-10-05").toEpochDay() * 86_400);
            assertThat(first.c()).isEqualByComparingTo("1.5");
            assertThat(first.v()).isEqualTo(10L);
            assertThat(response.candles().get(1).v()).isNull();
        }

        @Test
        @DisplayName("from and to bound the series, inclusive")
        void range() {
            CandleService.CandlesResponse response = candleService().candles("AAPL",
                    LocalDate.parse("2026-10-06"), LocalDate.parse("2026-10-06"));
            assertThat(response.candles()).hasSize(1);
        }

        @Test
        @DisplayName("a reversed range, an unknown symbol and a pending fetch are each refused clearly")
        void refusals() {
            CandleService service = candleService();
            assertThatThrownBy(() -> service.candles("AAPL", LocalDate.parse("2026-10-07"), LocalDate.parse("2026-10-01")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.candles("NOPE", null, null)).isInstanceOf(InstrumentNotFoundException.class);
            assertThatThrownBy(() -> service.candles("SLOW", null, null)).isInstanceOf(MarketDataUnavailableException.class);
        }
    }

    @Test
    @DisplayName("latest() reads without registering interest or waiting")
    void latestIsReadOnly() {
        when(mapper.findQuote("AAPL")).thenReturn(q("AAPL", new BigDecimal("1"), "USD", false));
        assertThat(service.latest("aapl")).isNotNull();
        verify(registry, never()).register(any(), any(), anyInt());
        verify(mapper, never()).isNotFound(any(), eq("VIEWED"), anyInt());
    }
}

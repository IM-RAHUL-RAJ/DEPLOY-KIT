package com.trading.tradeapi.advice;

import com.trading.tradeapi.advice.dto.AdviceResponse;
import com.trading.tradeapi.advice.dto.TradeSignal;
import com.trading.tradeapi.advice.service.AdviceService;
import com.trading.tradeapi.marketdata.CandleService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.portfolio.api.PortfolioPositions;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.watchlist.dto.WatchlistInstrumentDto;
import com.trading.tradeapi.watchlist.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Which instruments get a signal, and what each signal is fed, without a database. */
@DisplayName("AdviceService")
class AdviceServiceUnitTest {

    private PortfolioPositions portfolio;
    private WatchlistService watchlists;
    private CandleService candles;
    private MarketDataService marketData;
    private AdviceService service;

    @BeforeEach
    void setUp() {
        portfolio = mock(PortfolioPositions.class);
        watchlists = mock(WatchlistService.class);
        candles = mock(CandleService.class);
        marketData = mock(MarketDataService.class);
        service = new AdviceService(portfolio, watchlists, candles, marketData);
    }

    private static PricedPositionDto held(String symbol) {
        return new PricedPositionDto(1L, symbol, 5, new BigDecimal("100"), new BigDecimal("500"), new BigDecimal("110"),
                new BigDecimal("550"), new BigDecimal("50"), null, "USD", Instant.now(), false);
    }

    private static MarketQuote quote(String symbol) {
        return new MarketQuote(symbol, new BigDecimal("110"), null, null, "USD", null, null, "open", false,
                Instant.parse("2026-10-08T10:00:00Z"), Instant.now());
    }

    private static List<Double> rising() {
        return IntStream.range(0, 80).mapToObj(i -> 100.0 + i).toList();
    }

    private static WatchlistDto watching(String... symbols) {
        WatchlistDto list = mock(WatchlistDto.class);
        List<WatchlistInstrumentDto> items = java.util.Arrays.stream(symbols).map(s -> {
            WatchlistInstrumentDto i = mock(WatchlistInstrumentDto.class);
            when(i.symbol()).thenReturn(s);
            return i;
        }).toList();
        when(list.instruments()).thenReturn(items);
        return list;
    }

    @Nested
    @DisplayName("For an account")
    class ForAccount {

        @Test
        @DisplayName("Holdings come first, watched instruments follow once each, and a list with no instruments is skipped")
        void holdingsThenWatched() {
            when(portfolio.pricedPositions(1L)).thenReturn(List.of(held("aapl")));
            WatchlistDto empty = mock(WatchlistDto.class);
            List<WatchlistDto> lists = List.of(watching("AAPL", "MSFT"), watching("msft"), empty);
            when(watchlists.listWatchlists(1L, "u")).thenReturn(lists);
            when(candles.dailyCloses(any())).thenReturn(Map.of("AAPL", rising()));
            when(marketData.quotes(any())).thenReturn(Map.of("AAPL", quote("AAPL")));

            AdviceResponse response = service.forAccount(1L, "u");

            assertThat(response.signals()).extracting(TradeSignal::symbol).containsExactly("AAPL", "MSFT");
            assertThat(response.disclaimer()).isEqualTo(AdviceService.DISCLAIMER);
        }
    }

    @Nested
    @DisplayName("For one symbol")
    class ForSymbol {

        @Test
        @DisplayName("A held symbol is marked HOLDING; anything else is REQUESTED, with or without a quote")
        void sourceOfTheSignal() {
            when(portfolio.pricedPositions(1L)).thenReturn(List.of(held("AAPL"), held("MSFT")));
            when(candles.dailyCloses(any())).thenReturn(Map.of());
            when(marketData.quotes(List.of("AAPL"))).thenReturn(Map.of("AAPL", quote("AAPL")));
            when(marketData.quotes(List.of("TSLA"))).thenReturn(Map.of());

            assertThat(service.forSymbol(1L, " aapl ").symbol()).isEqualTo("AAPL");
            assertThat(service.forSymbol(1L, "tsla").symbol()).isEqualTo("TSLA");
        }
    }
}

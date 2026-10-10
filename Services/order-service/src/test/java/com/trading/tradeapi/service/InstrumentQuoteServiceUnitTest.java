package com.trading.tradeapi.service;

import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import static com.trading.tradeapi.marketdata.TestQuotes.*;
import com.trading.tradeapi.dto.InstrumentQuoteDto;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InstrumentQuoteServiceUnitTest {

    @Mock
    private InstrumentMapper instrumentMapper;

    @Mock
    private MarketDataService fauxnanceClient;

    @Mock
    private AlertService alertService;

    @InjectMocks
    private InstrumentQuoteService service;

    @Test
    @DisplayName("getQuote: returns the live quote with its native currency")
    void returnsLiveQuoteWithNativeCurrency() {
        when(fauxnanceClient.quote("INFY.NS"))
                .thenReturn(lookup(q("INFY.NS", new BigDecimal("1035"), "INR", false)));
        when(instrumentMapper.findBySymbol("INFY.NS")).thenReturn(null);

        InstrumentQuoteDto dto = service.getQuote("infy.ns");

        assertThat(dto.symbol()).isEqualTo("INFY.NS");
        assertThat(dto.quoteCurrency()).isEqualTo("INR");
        assertThat(dto.price()).isEqualByComparingTo("1035");
        assertThat(dto.stale()).isFalse();
    }

    @Test
    @DisplayName("getQuote: a live price is evaluated against the account's price alerts")
    void liveQuoteIsEvaluatedAgainstAlerts() {
        when(fauxnanceClient.quote("AAPL"))
                .thenReturn(lookup(q("AAPL", new BigDecimal("333.69"), "USD", false)));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(null);

        service.getQuote("AAPL");

        verify(alertService).evaluate(eq("AAPL"), any(BigDecimal.class), any(Instant.class));
    }

    @Test
    @DisplayName("getQuote: falls back to the catalogue currency when the quote omits it")
    void fallsBackToCatalogueCurrency() {
        when(fauxnanceClient.quote("TCS.NS"))
                .thenReturn(lookup(q("TCS.NS", new BigDecimal("2076"), null, false)));
        InstrumentRecord record = new InstrumentRecord();
        record.setQuoteCurrency("INR");
        when(instrumentMapper.findBySymbol("TCS.NS")).thenReturn(record);

        assertThat(service.getQuote("TCS.NS").quoteCurrency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("getQuote: an unknown symbol raises InstrumentNotFoundException")
    void unknownSymbolIsNotFound() {
        when(fauxnanceClient.quote("NOPE.XX")).thenReturn(missing());
        when(instrumentMapper.findBySymbol("NOPE.XX")).thenReturn(null);

        assertThatThrownBy(() -> service.getQuote("NOPE.XX"))
                .isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    @DisplayName("getQuote: the two-sided book reaches the client, not just the mid")
    void exposesBothSidesOfTheBook() {
        // The ticket shows what a market order is expected to fill at. Dropping the
        // book here left it showing the mid for both a buy and a sell.
        when(fauxnanceClient.quote("AAPL")).thenReturn(lookup(q(
                "AAPL", new BigDecimal("333.69"), new BigDecimal("333.50"),
                new BigDecimal("333.88"), "USD", false)));
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(null);

        InstrumentQuoteDto dto = service.getQuote("AAPL");

        assertThat(dto.bid()).isEqualByComparingTo("333.50");
        assertThat(dto.ask()).isEqualByComparingTo("333.88");
        assertThat(dto.indicativePrice(true)).isEqualByComparingTo("333.88");
        assertThat(dto.indicativePrice(false)).isEqualByComparingTo("333.50");
    }

    @Test
    @DisplayName("getQuote: a one-sided book falls back to the mid")
    void oneSidedBookFallsBackToMid() {
        when(fauxnanceClient.quote("MSFT")).thenReturn(lookup(q(
                "MSFT", new BigDecimal("415.20"), null, new BigDecimal("415.40"), "USD", false)));
        when(instrumentMapper.findBySymbol("MSFT")).thenReturn(null);

        InstrumentQuoteDto dto = service.getQuote("MSFT");

        assertThat(dto.bid()).isNull();
        assertThat(dto.indicativePrice(false)).isEqualByComparingTo("415.20");
        assertThat(dto.indicativePrice(true)).isEqualByComparingTo("415.40");
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Missing and partial data")
    class MissingAndPartial {

        @Test
        @DisplayName("No symbol is not found; nothing known yet is unavailable, not unknown")
        void nothingToShow() {
            assertThatThrownBy(() -> service.getQuote(null)).isInstanceOf(InstrumentNotFoundException.class);
            assertThatThrownBy(() -> service.getQuote("  ")).isInstanceOf(InstrumentNotFoundException.class);

            when(fauxnanceClient.quote("NEW")).thenReturn(new MarketDataService.QuoteLookup(null, false));
            assertThatThrownBy(() -> service.getQuote("new"))
                    .isInstanceOf(com.trading.tradeapi.exception.MarketDataUnavailableException.class);
        }

        @Test
        @DisplayName("A catalogued instrument with no quote yet answers its catalogue currency and no price")
        void catalogueOnly() {
            when(fauxnanceClient.quote("TCS.NS")).thenReturn(new MarketDataService.QuoteLookup(null, false));
            InstrumentRecord record = new InstrumentRecord(1L, "TCS.NS", "TCS", "EQUITY", "INR", "ACTIVE", null);
            when(instrumentMapper.findBySymbol("TCS.NS")).thenReturn(record);

            InstrumentQuoteDto dto = service.getQuote("tcs.ns");
            assertThat(dto.price()).isNull();
            assertThat(dto.quoteCurrency()).isEqualTo("INR");
            assertThat(dto.stale()).isFalse();
        }

        @Test
        @DisplayName("A quote without a price skips alerts; a failing alert check never fails the quote")
        void alertsAreBestEffort() {
            when(fauxnanceClient.quote("NOP")).thenReturn(lookup(q("NOP", null, null, true)));
            assertThat(service.getQuote("NOP").stale()).isTrue();
            org.mockito.Mockito.verifyNoInteractions(alertService);

            when(fauxnanceClient.quote("AAPL")).thenReturn(lookup(q("AAPL", new BigDecimal("190"), new BigDecimal("189"),
                    new BigDecimal("191"), "USD", false)));
            org.mockito.Mockito.doThrow(new IllegalStateException("alerts down"))
                    .when(alertService).evaluate(any(), any(), any());
            assertThat(service.getQuote("AAPL").ask()).isEqualByComparingTo("191");
        }
    }
}

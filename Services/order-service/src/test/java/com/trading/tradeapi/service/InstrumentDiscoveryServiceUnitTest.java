package com.trading.tradeapi.service;

import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import static com.trading.tradeapi.marketdata.TestQuotes.*;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class InstrumentDiscoveryServiceUnitTest {

    @Mock
    private InstrumentMapper instrumentMapper;
    @Mock
    private MarketDataService fauxnanceClient;

    private InstrumentDiscoveryService service;

    @BeforeEach
    public void setUp() {
        service = new InstrumentDiscoveryService(instrumentMapper, fauxnanceClient);
    }

    private InstrumentRecord record(String symbol, String status) {
        return new InstrumentRecord(1L, symbol, symbol, "EQUITY", "USD", status, null);
    }

    @Test
    @DisplayName("an ACTIVE catalogue instrument is returned without a Fauxnance lookup")
    public void knownActiveInstrumentReturned() {
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(record("AAPL", "ACTIVE"));

        InstrumentRecord result = service.resolveTradeableInstrument("aapl");

        assertNotNull(result);
        verifyNoInteractions(fauxnanceClient);
    }

    @Test
    @DisplayName("a suspended catalogue instrument is rejected and never resurrected")
    public void suspendedInstrumentRejected() {
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(record("AAPL", "SUSPENDED"));

        assertThrows(InstrumentNotFoundException.class,
                () -> service.resolveTradeableInstrument("AAPL"));
        verifyNoInteractions(fauxnanceClient);
        verify(instrumentMapper, never()).upsertDiscoveredInstrument(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an unknown symbol with a live Fauxnance quote is registered and returned")
    public void unknownSymbolDiscoveredViaFauxnance() {
        when(instrumentMapper.findBySymbol("INFY.NS"))
                .thenReturn(null)
                .thenReturn(record("INFY.NS", "ACTIVE"));
        when(fauxnanceClient.quote("INFY.NS"))
                .thenReturn(lookup(q("INFY.NS", new BigDecimal("1035"), "INR", false)));

        InstrumentRecord result = service.resolveTradeableInstrument("infy.ns");

        assertNotNull(result);
        verify(instrumentMapper).upsertDiscoveredInstrument("INFY.NS", "INFY.NS", "INR", "ACTIVE");
    }

    @Test
    @DisplayName("an unknown symbol Fauxnance cannot quote is rejected")
    public void undiscoverableSymbolRejected() {
        when(instrumentMapper.findBySymbol("NOPE")).thenReturn(null);
        when(fauxnanceClient.quote("NOPE")).thenReturn(missing());

        assertThrows(InstrumentNotFoundException.class,
                () -> service.resolveTradeableInstrument("NOPE"));
        verify(instrumentMapper, never()).upsertDiscoveredInstrument(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a Fauxnance response without a price is rejected")
    public void quoteWithoutPriceRejected() {
        when(instrumentMapper.findBySymbol("NOPE")).thenReturn(null);
        when(fauxnanceClient.quote("NOPE"))
                .thenReturn(lookup(q("NOPE", null, "USD", false)));

        assertThrows(InstrumentNotFoundException.class,
                () -> service.resolveTradeableInstrument("NOPE"));
        verify(instrumentMapper, never()).upsertDiscoveredInstrument(any(), any(), any(), any());
    }
}

package com.trading.tradeapi;

import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.service.InstrumentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class InstrumentServiceUnitTest {

    @Mock
    private InstrumentMapper instrumentMapper;

    private InstrumentService instrumentService;

    @BeforeEach
    public void setUp() {
        instrumentService = new InstrumentService(instrumentMapper);
    }

    @Test
    public void validateInstrumentExistsSuccessReturnsInstrument() {
        InstrumentRecord mockInstrument = createMockInstrument(1L, "AAPL", "AAPL", "ACTIVE");
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(mockInstrument);

        InstrumentRecord result = instrumentService.validateInstrumentExists("AAPL");

        assertThat(result).isNotNull();
        assertThat(result.getSymbol()).isEqualTo("AAPL");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(instrumentMapper).findBySymbol("AAPL");
    }

    @Test
    public void validateInstrumentExistsNotFoundThrowsException() {
        when(instrumentMapper.findBySymbol("INVALID")).thenReturn(null);

        assertThatThrownBy(() -> instrumentService.validateInstrumentExists("INVALID"))
                .isInstanceOf(InstrumentNotFoundException.class)
                .hasMessage("Instrument not found or not tradable");
    }

    @Test
    public void validateInstrumentTradeableSuccessWhenActive() {
        InstrumentRecord instrument = createMockInstrument(1L, "AAPL", "AAPL", "ACTIVE");

        // Should not throw
        instrumentService.validateInstrumentTradeable(instrument);
    }

    @Test
    public void validateInstrumentTradeableThrowsExceptionWhenDelisted() {
        InstrumentRecord instrument = createMockInstrument(1L, "OLD", "OLDSTOCK", "DELISTED");

        assertThatThrownBy(() -> instrumentService.validateInstrumentTradeable(instrument))
                .isInstanceOf(InstrumentNotFoundException.class)
                .hasMessage("Instrument not found or not tradable");
    }

    @Test
    public void validateInstrumentTradeableThrowsExceptionWhenDelistedCaseInsensitive() {
        InstrumentRecord instrument = createMockInstrument(1L, "OLD", "OLDSTOCK", "Delisted");

        assertThatThrownBy(() -> instrumentService.validateInstrumentTradeable(instrument))
                .isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    public void validateInstrumentTradeableThrowsExceptionWhenDelistedVariantCase() {
        InstrumentRecord instrument = createMockInstrument(1L, "OLD", "OLDSTOCK", "delisted");

        assertThatThrownBy(() -> instrumentService.validateInstrumentTradeable(instrument))
                .isInstanceOf(InstrumentNotFoundException.class);
    }

    @Test
    public void validateInstrumentTradeableSuccessWhenInactive() {
        InstrumentRecord instrument = createMockInstrument(1L, "INAC", "INAC", "INACTIVE");

        // Should not throw (only DELISTED is rejected)
        instrumentService.validateInstrumentTradeable(instrument);
    }

    @Test
    public void getInstrumentByIdSuccessReturnsInstrument() {
        InstrumentRecord mockInstrument = createMockInstrument(1L, "GOOGL", "GOOGL", "ACTIVE");
        when(instrumentMapper.findById(1L)).thenReturn(mockInstrument);

        InstrumentRecord result = instrumentService.getInstrumentById(1L);

        assertThat(result).isNotNull();
        assertThat(result.getInstrumentId()).isEqualTo(1L);
        assertThat(result.getSymbol()).isEqualTo("GOOGL");
        verify(instrumentMapper).findById(1L);
    }

    @Test
    public void getInstrumentByIdNotFoundReturnsNull() {
        when(instrumentMapper.findById(999L)).thenReturn(null);

        InstrumentRecord result = instrumentService.getInstrumentById(999L);

        assertThat(result).isNull();
        verify(instrumentMapper).findById(999L);
    }

    private InstrumentRecord createMockInstrument(Long instrumentId, String symbol, String ticker, String status) {
        InstrumentRecord instrument = new InstrumentRecord();
        instrument.setInstrumentId(instrumentId);
        instrument.setSymbol(symbol);
        instrument.setTicker(ticker);
        instrument.setAssetClass("EQUITY");
        instrument.setQuoteCurrency("USD");
        instrument.setStatus(status);
        return instrument;
    }
}

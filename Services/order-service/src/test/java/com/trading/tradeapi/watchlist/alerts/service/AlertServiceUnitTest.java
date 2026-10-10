package com.trading.tradeapi.watchlist.alerts.service;

import com.trading.tradeapi.watchlist.alerts.dto.CreatePriceAlertRequest;
import com.trading.tradeapi.watchlist.alerts.dto.PriceAlertDto;
import com.trading.tradeapi.watchlist.alerts.entity.PriceAlertRecord;
import com.trading.tradeapi.watchlist.alerts.mapper.PriceAlertMapper;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.notifications.api.AlertDelivery;
import com.trading.tradeapi.notifications.api.PriceAlertCrossed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The arming/evaluating logic in isolation. Crossing arithmetic is covered by
 * {@link PriceAlertEvaluatorTest}; the end-to-end seam (alert -&gt; inbox row) is covered by
 * {@link com.trading.tradeapi.watchlist.alerts.AlertsIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Alert service")
class AlertServiceUnitTest {

    @Mock
    private PriceAlertMapper mapper;

    @Mock
    private InstrumentMapper instrumentMapper;

    @Mock
    private AlertDelivery alertDelivery;

    private AlertService service;

    @BeforeEach
    void setUp() {
        service = new AlertService(mapper, instrumentMapper, alertDelivery);
    }

    @Test
    @DisplayName("An unknown symbol is VAL-422")
    void unknownSymbolIsRejected() {
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(null);

        assertThatThrownBy(() -> service.create(1L, new CreatePriceAlertRequest("AAPL", "ABOVE", big("240.00"))))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("VAL-422");
        verify(mapper, never()).insertActive(any());
    }

    @Test
    @DisplayName("The same active alert again returns the existing one, no second row")
    void duplicateIsReturnedNotRecreated() {
        PriceAlertRecord alert = alertRecord(1L, 1L, "AAPL", "ABOVE", "240.00", "ACTIVE");
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(new InstrumentRecord(1L, "AAPL", "AAPL",
                "EQUITY", "USD", "ACTIVE", null));
        when(mapper.findActiveByAccountSymbolDirectionThreshold(1L, "AAPL", "ABOVE", big("240.00")))
                .thenReturn(alert);

        PriceAlertDto dto = service.create(1L, new CreatePriceAlertRequest("AAPL", "ABOVE", big("240.00")));

        assertThat(dto.alertId()).isEqualTo(1L);
        verify(mapper, never()).insertActive(any());
    }

    @Test
    @DisplayName("An active alert at the cap is refused with VAL-422")
    void capStopsAtTwenty() {
        when(instrumentMapper.findBySymbol("XYZ")).thenReturn(new InstrumentRecord(1L, "XYZ", "XYZ",
                "EQUITY", "USD", "ACTIVE", null));
        when(mapper.countActiveByAccount(1L)).thenReturn(20);

        assertThatThrownBy(() -> service.create(1L, new CreatePriceAlertRequest("XYZ", "BELOW", big("5.00"))))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("VAL-422");
        verify(mapper, never()).insertActive(any());
    }

    @Test
    @DisplayName("A symbol uppercase normalises before the catalogue lookup")
    void symbolIsNormalised() {
        PriceAlertRecord alert = alertRecord(2L, 1L, "AAPL", "BELOW", "200.00", "ACTIVE");
        when(instrumentMapper.findBySymbol("AAPL")).thenReturn(new InstrumentRecord(1L, "AAPL", "AAPL",
                "EQUITY", "USD", "ACTIVE", null));
        when(mapper.findActiveByAccountSymbolDirectionThreshold(1L, "AAPL", "BELOW", big("200.00")))
                .thenReturn(null);
        when(mapper.countActiveByAccount(1L)).thenReturn(0);
        alert.setPriceAlertId(2L);
        when(mapper.insertActive(any())).thenAnswer(inv -> {
            inv.<PriceAlertRecord>getArgument(0).setPriceAlertId(2L);
            return 1;
        });
        when(mapper.findById(2L)).thenReturn(alert);

        PriceAlertDto dto = service.create(1L, new CreatePriceAlertRequest(" aapl ", " below ", big("200.00")));

        assertThat(dto.symbol()).isEqualTo("AAPL");
        assertThat(dto.direction()).isEqualTo("BELOW");
    }

    @Test
    @DisplayName("Cancelling a foreign alert is ACC-404")
    void cancelForeignIsNotFound() {
        PriceAlertRecord foreign = alertRecord(9L, 9L, "AAPL", "ABOVE", "240.00", "ACTIVE");
        when(mapper.findById(9L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.cancel(1L, 9L))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("ACC-404");
        verify(mapper, never()).markCancelled(any(), any());
    }

    @Test
    @DisplayName("Cancelling a crossed alert is idempotent and keeps TRIGGERED")
    void cancelIsIdempotent() {
        PriceAlertRecord triggered = alertRecord(4L, 1L, "AAPL", "ABOVE", "240.00", "TRIGGERED");
        when(mapper.findById(4L)).thenReturn(triggered);

        PriceAlertDto dto = service.cancel(1L, 4L);

        assertThat(dto.status()).isEqualTo("TRIGGERED");
        verify(mapper, never()).markCancelled(any(), any());
    }

    @Test
    @DisplayName("A crossing flips the alert and delivers exactly the crossed event")
    void crossingTriggersAndDelivers() {
        PriceAlertRecord alert = alertRecord(7L, 1L, "AAPL", "ABOVE", "240.00", "ACTIVE");
        when(mapper.findActiveBySymbol("AAPL")).thenReturn(List.of(alert));
        when(mapper.markTriggered(eq(7L), eq(big("241.00")), any())).thenReturn(1);

        service.evaluate("AAPL", big("241.00"), Instant.parse("2026-10-19T14:00:00Z"));

        ArgumentCaptor<PriceAlertCrossed> captor = ArgumentCaptor.forClass(PriceAlertCrossed.class);
        verify(alertDelivery).deliverPriceAlert(captor.capture());
        PriceAlertCrossed crossed = captor.getValue();
        assertThat(crossed.eventId()).isEqualTo("AAPL-7@2026-10-19T14:00:00Z");
        assertThat(crossed.accountId()).isEqualTo(1L);
        assertThat(crossed.symbol()).isEqualTo("AAPL");
        assertThat(crossed.direction()).isEqualTo("ABOVE");
        assertThat(crossed.threshold()).isEqualByComparingTo("240.00");
        assertThat(crossed.lastPrice()).isEqualByComparingTo("241.00");
        assertThat(crossed.triggeredAt()).isNotNull();
    }

    @Test
    @DisplayName("A redelivered message finds the alert already TRIGGERED and delivers nothing")
    void replayDoesNotDoubleDeliver() {
        PriceAlertRecord alert = alertRecord(7L, 1L, "AAPL", "ABOVE", "240.00", "ACTIVE");
        when(mapper.findActiveBySymbol("AAPL")).thenReturn(List.of(alert));
        when(mapper.markTriggered(eq(7L), eq(big("241.00")), any())).thenReturn(0);

        service.evaluate("AAPL", big("241.00"), Instant.parse("2026-10-19T14:00:00Z"));

        verify(alertDelivery, never()).deliverPriceAlert(any());
    }

    @Test
    @DisplayName("A quote that has not crossed touches nothing")
    void noCrossNoWrite() {
        PriceAlertRecord alert = alertRecord(7L, 1L, "AAPL", "ABOVE", "240.00", "ACTIVE");
        when(mapper.findActiveBySymbol("AAPL")).thenReturn(List.of(alert));

        service.evaluate("AAPL", big("239.99"), Instant.parse("2026-10-19T14:00:00Z"));

        verify(mapper, never()).markTriggered(any(), any(), any());
        verify(alertDelivery, never()).deliverPriceAlert(any());
    }

    @Test
    @DisplayName("No active alerts for a symbol means no work")
    void emptySymbolIsNoWork() {
        when(mapper.findActiveBySymbol("ZZZZ")).thenReturn(List.of());

        service.evaluate("ZZZZ", big("999.00"), Instant.parse("2026-10-19T14:00:00Z"));

        verify(mapper, never()).markTriggered(any(), any(), any());
        verify(alertDelivery, never()).deliverPriceAlert(any());
    }

    private static PriceAlertRecord alertRecord(Long id, Long accountId, String symbol, String direction,
                                                String threshold, String status) {
        PriceAlertRecord alert = new PriceAlertRecord(accountId, symbol, direction, big(threshold));
        alert.setPriceAlertId(id);
        alert.setStatus(status);
        alert.setCreatedAt(Instant.parse("2026-10-01T09:00:00Z"));
        return alert;
    }

    private static BigDecimal big(String value) {
        return new BigDecimal(value);
    }
}
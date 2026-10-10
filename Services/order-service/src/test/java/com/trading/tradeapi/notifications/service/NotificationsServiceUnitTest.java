package com.trading.tradeapi.notifications.service;

import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.notifications.dto.NotificationDto;
import com.trading.tradeapi.notifications.entity.NotificationRecord;
import com.trading.tradeapi.notifications.mapper.NotificationMapper;
import com.trading.tradeapi.notifications.service.NotificationService.OrderOutcome;
import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.api.AlertChannelResolver;
import com.trading.tradeapi.preferences.api.ResolvedChannel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The recording path in isolation: idempotency, channel resolution, dispatch outcomes and the
 * two inbox operations. The Kafka decode is covered by {@link TradeEventPayloadTest}; the hard
 * integration is covered by {@link com.trading.tradeapi.notifications.NotificationsIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Notification service")
class NotificationsServiceUnitTest {

    @Mock
    private NotificationMapper mapper;

    @Mock
    private AlertChannelResolver resolver;

    @Mock
    private ChannelDispatcher dispatcher;

    private NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(mapper, resolver, dispatcher);
    }

    @Test
    @DisplayName("A filled order is recorded with a QUEUED insert, dispatched, and closed SENT")
    void filledOrderIsRecordedAndSent() {
        when(resolver.resolveChannelForAccount(1L, "ORDER_FILLED"))
                .thenReturn(ResolvedChannel.of(AlertChannel.EMAIL, "alex@example.com"));
        when(dispatcher.sendEmail(eq("alex@example.com"), any(), any())).thenReturn(true);

        service.recordOrderOutcome(outcome("evt-1", "ORDER_FILLED"));

        verify(mapper).insert(any(NotificationRecord.class));
        verify(mapper).updateStatus(any(), eq("SENT"));
    }

    @Test
    @DisplayName("A replayed eventId books nothing and sends nothing a second time")
    void replayIsIdempotent() {
        when(mapper.findByEventId("evt-1")).thenReturn(new NotificationRecord());

        service.recordOrderOutcome(outcome("evt-1", "ORDER_FILLED"));

        verify(mapper, never()).insert(any());
        verify(dispatcher, never()).sendEmail(any(), any(), any());
    }

    @Test
    @DisplayName("A customer whose channel is NONE gets a SUPPRESSED row, not a send")
    void noneChannelIsSuppressedNotSent() {
        when(resolver.resolveChannelForAccount(1L, "ORDER_REJECTED")).thenReturn(ResolvedChannel.none());

        service.recordOrderOutcome(outcome("evt-1", "ORDER_REJECTED"));

        verify(mapper).updateStatus(any(), eq("SUPPRESSED"));
        verify(dispatcher, never()).sendEmail(any(), any(), any());
        verify(dispatcher, never()).sendPush(any(), any());
    }

    @Test
    @DisplayName("A provider outage records FAILED on the row instead of throwing")
    void providerFailureIsRecordedNotThrown() {
        when(resolver.resolveChannelForAccount(1L, "ORDER_FILLED"))
                .thenReturn(ResolvedChannel.of(AlertChannel.EMAIL, "alex@example.com"));
        when(dispatcher.sendEmail(eq("alex@example.com"), any(), any())).thenReturn(false);

        service.recordOrderOutcome(outcome("evt-1", "ORDER_FILLED"));

        verify(mapper).updateStatus(any(), eq("FAILED"));
    }

    @Test
    @DisplayName("An event without an account id is dropped, not recorded")
    void missingAccountIsIgnored() {
        service.recordOrderOutcome(new OrderOutcome("evt-1", "ORDER_FILLED", null, "AAPL",
                1L, "BUY", 10L, new BigDecimal("232.71"), null));

        verify(mapper, never()).insert(any());
    }

    @Test
    @DisplayName("Marking someone else's notification read is ACC-404, never a hint it exists")
    void markReadOnForeignNotificationIsNotFound() {
        NotificationRecord foreign = new NotificationRecord();
        foreign.setAccountId(9L);
        when(mapper.findById(42L)).thenReturn(foreign);

        assertThatThrownBy(() -> service.markRead(1L, 42L))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("ACC-404");
        verify(mapper, never()).markRead(eq(42L), eq(1L));
    }

    @Test
    @DisplayName("Marking your own notification read returns it with the read flag")
    void markReadReturnsTheUpdatedRecord() {
        NotificationRecord mine = new NotificationRecord();
        mine.setNotificationId(42L);
        mine.setAccountId(1L);
        mine.setReadAt(null);
        when(mapper.findById(42L)).thenReturn(mine);
        when(mapper.markRead(42L, 1L)).thenReturn(1);
        NotificationRecord updated = new NotificationRecord();
        updated.setNotificationId(42L);
        updated.setAccountId(1L);
        updated.setReadAt(Instant.parse("2026-10-19T14:05:11Z"));
        when(mapper.findById(42L)).thenReturn(updated);

        NotificationDto dto = service.markRead(1L, 42L);

        assertThat(dto.read()).isTrue();
        verify(mapper).markRead(42L, 1L);
    }

    @Test
    @DisplayName("A limit outside 1..100 is VAL-422")
    void invalidLimitIsRejected() {
        assertThatThrownBy(() -> service.listFor(1L, null, 1000))
                .isInstanceOf(DomainException.class)
                .extracting("code").isEqualTo("VAL-422");
    }

    private static OrderOutcome outcome(String eventId, String category) {
        return new OrderOutcome(eventId, category, 1L, "AAPL", 7L, "BUY", 40L,
                new BigDecimal("232.71"), null);
    }

    @org.junit.jupiter.api.Nested
    @DisplayName("Delivery channels and the inbox limit")
    class ChannelsAndLimits {

        @Test
        @DisplayName("In-app needs no contact; both shows the toast and is SENT when the email went")
        void inAppAndBoth() {
            when(resolver.resolveChannelForAccount(1L, "ORDER_FILLED"))
                    .thenReturn(ResolvedChannel.of(AlertChannel.PUSH, null))
                    .thenReturn(ResolvedChannel.of(AlertChannel.BOTH, "alex@example.com"));
            when(dispatcher.sendPush(eq("in-app"), any())).thenReturn(true);
            when(dispatcher.sendEmail(eq("alex@example.com"), any(), any())).thenReturn(true);

            service.recordOrderOutcome(outcome("evt-1", "ORDER_FILLED"));
            service.recordOrderOutcome(outcome("evt-2", "ORDER_FILLED"));

            org.mockito.Mockito.verify(dispatcher, org.mockito.Mockito.times(2)).sendPush(eq("in-app"), any());
            org.mockito.Mockito.verify(dispatcher).sendEmail(eq("alex@example.com"), any(), any());
            org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).updateStatus(any(), eq("SENT"));
        }

        @Test
        @DisplayName("The inbox answers up to its maximum and refuses a limit outside it")
        void limits() {
            service.listFor(1L, null, null);
            service.listFor(1L, true, 5);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.listFor(1L, false, 0))
                    .hasMessageContaining("limit must be between");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.listFor(1L, false, 100_000))
                    .hasMessageContaining("limit must be between");
        }
    }
}

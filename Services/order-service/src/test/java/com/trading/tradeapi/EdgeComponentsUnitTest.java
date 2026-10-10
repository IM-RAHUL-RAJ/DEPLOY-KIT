package com.trading.tradeapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.entity.HoldingRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.kafka.producer.TradeEventProducer;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.notifications.kafka.TradeEventConsumer;
import com.trading.tradeapi.notifications.service.ChannelDispatcher;
import com.trading.tradeapi.notifications.service.NotificationService;
import com.trading.tradeapi.notifications.service.channel.PushSender;
import com.trading.tradeapi.preferences.controller.NotificationPreferenceController;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesPutRequest;
import com.trading.tradeapi.preferences.service.NotificationPreferenceService;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.EmailConfigurationService;
import com.trading.tradeapi.strategy.kafka.StrategyMarketDataConsumer;
import com.trading.tradeapi.strategy.service.StrategyEngine;
import com.trading.tradeapi.watchlist.controller.WatchlistController;
import com.trading.tradeapi.watchlist.dto.AddWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.watchlist.service.WatchlistService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Small adapters at the edges of the order service: controllers, Kafka seams and the channel dispatcher. */
@DisplayName("Edge components")
class EdgeComponentsUnitTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest signedIn(Long accountId, String uuid) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (accountId != null) {
            request.setAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR, accountId);
        }
        if (uuid != null) {
            request.setAttribute(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR, uuid);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Nested
    @DisplayName("Single-list watchlist controller")
    class WatchlistControllerTests {

        private final WatchlistService service = mock(WatchlistService.class);
        private final WatchlistController controller = new WatchlistController(service);
        private final WatchlistDto list = mock(WatchlistDto.class);

        @Test
        @DisplayName("Without an account claim every route is 403, and the service is never asked")
        void noAccountIsForbidden() {
            signedIn(null, "u-1");
            assertThat(controller.getWatchlist().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(controller.addInstrument(new AddWatchlistInstrumentRequest("AAPL")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            RequestContextHolder.resetRequestAttributes();
            assertThat(controller.removeInstrument(1L).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            verifyNoInteractions(service);
        }

        @Test
        @DisplayName("Reads, adds and removes for the signed-in account")
        void succeeds() {
            signedIn(1L, "u-1");
            when(service.getWatchlist(1L, "u-1")).thenReturn(list);
            when(service.addInstrument(eq(1L), eq("u-1"), any())).thenReturn(list);
            when(service.removeInstrument(1L, "u-1", 5L)).thenReturn(list);

            assertThat(controller.getWatchlist().getBody()).isSameAs(list);
            assertThat(controller.addInstrument(new AddWatchlistInstrumentRequest("AAPL")).getBody()).isSameAs(list);
            assertThat(controller.removeInstrument(5L).getBody()).isSameAs(list);
        }

        @Test
        @DisplayName("A refused request is 403 or 400; anything unexpected is 500")
        void failures() {
            signedIn(1L, "u-1");
            when(service.getWatchlist(1L, "u-1")).thenThrow(new IllegalArgumentException("not yours"))
                    .thenThrow(new IllegalStateException("db"));
            when(service.addInstrument(eq(1L), eq("u-1"), any())).thenThrow(new IllegalArgumentException("bad"))
                    .thenThrow(new IllegalStateException("db"));
            when(service.removeInstrument(1L, "u-1", 5L)).thenThrow(new IllegalArgumentException("bad"))
                    .thenThrow(new IllegalStateException("db"));

            assertThat(controller.getWatchlist().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(controller.getWatchlist().getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            AddWatchlistInstrumentRequest add = new AddWatchlistInstrumentRequest("AAPL");
            assertThat(controller.addInstrument(add).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.addInstrument(add).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(controller.removeInstrument(5L).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(controller.removeInstrument(5L).getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    @Nested
    @DisplayName("Notification preference controller")
    class NotificationPreferenceControllerTests {

        private final NotificationPreferenceService service = mock(NotificationPreferenceService.class);
        private final NotificationPreferenceController controller = new NotificationPreferenceController(service);

        @Test
        @DisplayName("Reads and writes only the token's own account")
        void ownAccountOnly() {
            NotificationPreferencesPutRequest body = new NotificationPreferencesPutRequest(List.of());
            controller.get(1L, signedIn(1L, "u"));
            controller.put(1L, body, signedIn(1L, "u"));
            verify(service).get(1L);
            verify(service).put(1L, body);

            assertThatThrownBy(() -> controller.get(2L, signedIn(1L, "u"))).isInstanceOf(UnauthorizedAccountAccessException.class);
            assertThatThrownBy(() -> controller.put(1L, body, signedIn(null, "u"))).isInstanceOf(UnauthorizedAccountAccessException.class);
        }
    }

    @Nested
    @DisplayName("Channel dispatcher")
    class ChannelDispatcherTests {

        private final EmailConfigurationService emailConfig = mock(EmailConfigurationService.class);
        private final JavaMailSender mail = mock(JavaMailSender.class);
        private final PushSender push = mock(PushSender.class);

        @Test
        @DisplayName("Sends email when configured, and reports a refused send")
        void email() {
            when(emailConfig.isConfigured()).thenReturn(true);
            when(emailConfig.getFromAddress()).thenReturn("no-reply@test");
            ChannelDispatcher dispatcher = new ChannelDispatcher(Optional.of(mail), emailConfig, push);

            assertThat(dispatcher.sendEmail("a@b.co", "Order filled", "body")).isTrue();
            ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(mail).send(sent.capture());
            assertThat(sent.getValue().getSubject()).isEqualTo("Akatsuki Terminal: Order filled");

            doThrow(new MailSendException("refused")).when(mail).send(any(SimpleMailMessage.class));
            assertThat(dispatcher.sendEmail("a@b.co", "t", "b")).isFalse();
        }

        @Test
        @DisplayName("Without SMTP nothing is sent; in-app goes to its sender")
        void unconfiguredAndOtherChannels() {
            when(emailConfig.isConfigured()).thenReturn(false, true);
            ChannelDispatcher noSender = new ChannelDispatcher(Optional.empty(), emailConfig, push);
            assertThat(noSender.sendEmail("a@b.co", "t", "b")).isFalse();
            assertThat(noSender.sendEmail(null, "t", "b")).isFalse();

            when(push.deliverPush("in-app", "b")).thenReturn(true);
            assertThat(noSender.sendPush("in-app", "b")).isTrue();
        }
    }

    @Nested
    @DisplayName("Kafka seams")
    class KafkaSeams {

        @SuppressWarnings("unchecked")
        private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
        private final HoldingMapper holdings = mock(HoldingMapper.class);

        private OrderRecord order() {
            OrderRecord order = new OrderRecord();
            order.setOrderId(9L);
            order.setAccountId(1L);
            order.setTicker("AAPL");
            order.setSide(OrderSide.SELL);
            order.setQuantity(new BigDecimal("3"));
            order.setPrice(new BigDecimal("190"));
            return order;
        }

        @Test
        @DisplayName("A cancellation is ORDER_CANCELLED with the unchanged position")
        void publishesCancellation() throws Exception {
            HoldingRecord holding = new HoldingRecord();
            holding.setQuantity(new BigDecimal("10"));
            holding.setAveragePrice(new BigDecimal("150"));
            when(holdings.findByAccountAndTicker(1L, "AAPL")).thenReturn(holding);
            when(kafka.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

            TradeEventProducer producer = new TradeEventProducer(kafka, mapper, holdings);
            org.springframework.test.util.ReflectionTestUtils.setField(producer, "topic", "trade-events");
            producer.publishCancelled(order());

            ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
            verify(kafka).send(eq("trade-events"), eq("1"), json.capture());
            JsonNode payload = mapper.readTree(json.getValue()).path("payload");
            assertThat(payload.path("status").asText()).isEqualTo("CANCELLED");
            assertThat(payload.path("positionQuantityAfter").asLong()).isEqualTo(10L);
        }

        @Test
        @DisplayName("No holding is a zero position; an unacknowledged or failed send is only logged")
        void cancellationEdgeCases() {
            OrderRecord bare = new OrderRecord();
            bare.setOrderId(9L);
            bare.setAccountId(1L);
            when(kafka.send(anyString(), anyString(), anyString()))
                    .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker")))
                    .thenThrow(new IllegalStateException("no broker"));
            TradeEventProducer producer = new TradeEventProducer(kafka, mapper, holdings);
            org.springframework.test.util.ReflectionTestUtils.setField(producer, "topic", "trade-events");
            producer.publishCancelled(bare);
            producer.publishCancelled(bare);
            verify(kafka, org.mockito.Mockito.times(2)).send(anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("Trade events become inbox notifications; other or broken messages are skipped")
        void tradeEventConsumer() {
            NotificationService notifications = mock(NotificationService.class);
            TradeEventConsumer consumer = new TradeEventConsumer(notifications, mapper);

            consumer.consume("""
                    {"eventId":"e-1","eventType":"ORDER_FILLED","eventTime":"2026-10-08T10:00:00Z","source":"x",
                     "schemaVersion":1,"payload":{"orderId":9,"accountId":1,"symbol":"AAPL","side":"BUY",
                     "quantity":3,"executedPrice":190}}""");
            verify(notifications).recordOrderOutcome(any());

            consumer.consume("not json");
            consumer.consume("{\"eventId\":\"e-2\",\"eventType\":\"QUOTE\",\"payload\":{}}");
            consumer.consume("{\"eventType\":\"ORDER_REJECTED\",\"payload\":{}}");
            verify(notifications, org.mockito.Mockito.times(1)).recordOrderOutcome(any());
        }

        @Test
        @DisplayName("Quotes on market-data reach the strategy engine; anything else is skipped")
        void strategyConsumer() {
            StrategyEngine engine = mock(StrategyEngine.class);
            StrategyMarketDataConsumer consumer = new StrategyMarketDataConsumer(engine, mapper);
            consumer.consume("{\"eventType\":\"QUOTE\",\"payload\":{\"symbol\":\"aapl\",\"price\":190}}");
            consumer.consume("{\"eventType\":\"HEARTBEAT\"}");
            verify(engine).onQuote(any());
        }
    }

    @Nested
    @DisplayName("QuoteEvent decoding")
    class QuoteEventDecoding {

        @Test
        @DisplayName("Reads a quote, a double-encoded one, and falls back from quoteAsOf to eventTime to now")
        void decodes() throws Exception {
            String quote = "{\"eventId\":\"e\",\"eventType\":\"QUOTE\",\"eventTime\":\"2026-10-08T10:00:00Z\","
                    + "\"payload\":{\"symbol\":\"aapl\",\"price\":190,\"quoteAsOf\":\"bad\"}}";
            var first = com.trading.tradeapi.marketdata.QuoteEvent.decode(mapper, quote).orElseThrow();
            assertThat(first.symbol()).isEqualTo("AAPL");
            assertThat(first.observedAt()).isEqualTo(java.time.Instant.parse("2026-10-08T10:00:00Z"));

            var doubled = com.trading.tradeapi.marketdata.QuoteEvent.decode(mapper, mapper.writeValueAsString(
                    "{\"eventType\":\"QUOTE\",\"payload\":{\"symbol\":\"MSFT\",\"price\":1}}"));
            assertThat(doubled).isPresent();
        }

        @Test
        @DisplayName("Anything that is not a priced quote is empty")
        void rejects() {
            for (String message : List.of("not json", "[1,2]", "null",
                    "{\"eventType\":\"TRADE\"}",
                    "{\"eventType\":\"QUOTE\",\"payload\":{\"symbol\":\" \",\"price\":1}}",
                    "{\"eventType\":\"QUOTE\",\"payload\":{\"symbol\":\"A\",\"price\":\"x\"}}")) {
                assertThat(com.trading.tradeapi.marketdata.QuoteEvent.decode(mapper, message)).isEmpty();
            }
        }
    }
}

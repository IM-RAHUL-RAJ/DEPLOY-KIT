package com.trading.tradeapi.notifications;

import org.junit.jupiter.api.AfterEach;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.TestTokenFactory;
import com.trading.tradeapi.notifications.dto.NotificationDto;
import com.trading.tradeapi.notifications.service.NotificationService;
import com.trading.tradeapi.notifications.service.NotificationService.OrderOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The notifications module over HTTP with the real filter, the real service and the real
 * mapper. Seed data: account 1 and account 2 both belong to different users, so they are never
 * the same customer. Its own database, like the preferences test.
 *
 * <p>The Kafka consumer is switched off in {@code test/application.yml}; the decode half is
 * covered by {@link com.trading.tradeapi.notifications.service.TradeEventPayloadTest} and the
 * recording half by driving this service directly, which is the same code the listener calls.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:notifications-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@DisplayName("Customer notifications module")
class NotificationsIntegrationTest {
    private static final String BASE = "/api/v1/notifications";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void clearModuleTables() {
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM customer_preference");
    }

    private void seedPreference(long accountId, String channel, String contact) {
        jdbc.update("INSERT INTO customer_preference (account_id, alert_channel, alert_contact) VALUES (?, ?, ?)",
                accountId, channel, contact);
    }

    private OrderOutcome outcome(String eventId, String category, long accountId, String symbol) {
        return new OrderOutcome(eventId, category, accountId, symbol, 7L, "BUY", 40L,
                new BigDecimal("232.71"), null);
    }

    private String tokenFor(long accountId) {
        return TestTokenFactory.createToken(accountId, jwtSecret, 60_000L);
    }

    private List<NotificationDto> listFor(long accountId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
    }

    private List<NotificationDto> listForUnread(long accountId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/" + accountId + "?unread=true")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
    }

    private int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        return n == null ? 0 : n;
    }

    private long inboxIdOf(String eventId) {
        Long id = jdbc.queryForObject("SELECT notification_id FROM notification WHERE event_id = ?",
                Long.class, eventId);
        return id == null ? 0L : id;
    }

    @Nested
    @DisplayName("The three probes every route in this sprint has to survive")
    class TheThreeProbesEveryRouteInThisSprintHasToSurvive {

        @Test
        @DisplayName("Refused with no token")
        void noTokenIsRefused() throws Exception {
            mockMvc.perform(get(BASE + "/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        }

        @Test
        @DisplayName("Refused when the token's account is not the account in the path")
        void crossAccountAccessIsForbiddenNotNotFound() throws Exception {
            mockMvc.perform(get(BASE + "/1").header("Authorization", "Bearer " + tokenFor(2L)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ACC-403"));
        }
    }

    @Nested
    @DisplayName("Recording and delivery")
    class RecordingAndDelivery {

        @Test
        @DisplayName("A fill resolves the customer's in-app channel, records SENT and reaches the inbox")
        void inAppNotificationIsRecordedSentAndListed() throws Exception {
            seedPreference(1L, "PUSH", null);

            notificationService.recordOrderOutcome(outcome("evt-fill-1", "ORDER_FILLED", 1L, "AAPL"));

            List<NotificationDto> inbox = listFor(1L, tokenFor(1L));
            assertThat(inbox).hasSize(1);
            NotificationDto dto = inbox.get(0);
            assertThat(dto.category()).isEqualTo("ORDER_FILLED");
            assertThat(dto.channel()).isEqualTo("PUSH");
            assertThat(dto.status()).isEqualTo("SENT");
            assertThat(dto.read()).isFalse();
        }

        @Test
        @DisplayName("A redelivered eventId books one row and sends once, never twice")
        void replayDeliversOnce() throws Exception {
            seedPreference(1L, "PUSH", null);

            notificationService.recordOrderOutcome(outcome("evt-replay-1", "ORDER_FILLED", 1L, "AAPL"));
            notificationService.recordOrderOutcome(outcome("evt-replay-1", "ORDER_FILLED", 1L, "AAPL"));

            assertThat(count()).isEqualTo(1);
            assertThat(listFor(1L, tokenFor(1L))).hasSize(1);
        }

        @Test
        @DisplayName("A NONE channel is SUPPRESSED in storage and does not appear in the in-app inbox")
        void noneChannelIsSuppressedAndHiddenFromInbox() throws Exception {
            // Account 2 has no preference row at all: the resolver answers NONE.
            notificationService.recordOrderOutcome(outcome("evt-supp-1", "ORDER_REJECTED", 2L, "GOOGL"));

            List<NotificationDto> inbox = listFor(2L, tokenFor(2L));
            assertThat(inbox).isEmpty();
            assertThat(jdbc.queryForObject("SELECT channel FROM notification WHERE event_id = ?", String.class, "evt-supp-1"))
                .isEqualTo("NONE");
            assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE event_id = ?", String.class, "evt-supp-1"))
                .isEqualTo("SUPPRESSED");
        }

        @Test
        @DisplayName("Email-only notifications stay off the in-app inbox even when delivery fails")
        void emailOnlyNotificationsAreHiddenFromInbox() throws Exception {
            seedPreference(1L, "EMAIL", "alex@example.com");

            notificationService.recordOrderOutcome(outcome("evt-mail-1", "ORDER_FILLED", 1L, "AAPL"));

            List<NotificationDto> inbox = listFor(1L, tokenFor(1L));
            assertThat(inbox).isEmpty();
            assertThat(jdbc.queryForObject("SELECT channel FROM notification WHERE event_id = ?", String.class, "evt-mail-1"))
                .isEqualTo("EMAIL");
            assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE event_id = ?", String.class, "evt-mail-1"))
                .isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("The inbox")
    class TheInbox {

        @Test
        @DisplayName("Newest first, and unread=true filters out marked-read records")
        void inboxIsNewestFirstAndUnreadFilters() throws Exception {
            seedPreference(1L, "PUSH", null);

            notificationService.recordOrderOutcome(outcome("evt-a", "ORDER_CANCELLED", 1L, "AAPL"));
            notificationService.recordOrderOutcome(outcome("evt-b", "ORDER_FILLED", 1L, "GOOGL"));

            List<NotificationDto> all = listFor(1L, tokenFor(1L));
            assertThat(all).hasSize(2);
            assertThat(all.get(0).category()).isEqualTo("ORDER_FILLED");
            assertThat(all.get(1).category()).isEqualTo("ORDER_CANCELLED");

            notificationService.markRead(1L, inboxIdOf("evt-a"));

            List<NotificationDto> unread = listForUnread(1L, tokenFor(1L));
            assertThat(unread).hasSize(1);
            assertThat(unread.get(0).category()).isEqualTo("ORDER_FILLED");
        }

        @Test
        @DisplayName("Marking read is idempotent and returns the record with read=true")
        void markReadIsIdempotent() throws Exception {
            seedPreference(1L, "PUSH", null);
            notificationService.recordOrderOutcome(outcome("evt-idem", "ORDER_FILLED", 1L, "AAPL"));
            Long id = inboxIdOf("evt-idem");

            mockMvc.perform(post(BASE + "/1/" + id + "/read").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.read").value(true));
            mockMvc.perform(post(BASE + "/1/" + id + "/read").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.read").value(true));
        }

        @Test
        @DisplayName("A notification belonging to another account reads as not found, never as a hint")
        void foreignNotificationIsNotFound() throws Exception {
            seedPreference(1L, "PUSH", null);
            notificationService.recordOrderOutcome(outcome("evt-foreign", "ORDER_FILLED", 1L, "AAPL"));
            Long id = inboxIdOf("evt-foreign");

            mockMvc.perform(post(BASE + "/2/" + id + "/read").header("Authorization", "Bearer " + tokenFor(2L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ACC-404"));
        }
    }

    @Nested
    @DisplayName("Orders whose trade event never arrived")
    class TheSafetyNet {

        @Autowired
        private com.trading.tradeapi.notifications.mapper.NotificationMapper mapper;

        private com.trading.tradeapi.notifications.service.OrderOutcomeSweeper sweeper() {
            return new com.trading.tradeapi.notifications.service.OrderOutcomeSweeper(mapper, notificationService, 24);
        }

        private long fill(String key) {
            jdbc.update("INSERT INTO orders (account_id, ticker, side, quantity, price, order_type, status, idempotency_key,"
                    + " executed_price, executed_on) VALUES (1, 'AAPL', 'BUY', 3, 190, 'MARKET', 'FILLED', ?, 191.5, CURRENT_TIMESTAMP)", key);
            return jdbc.queryForObject("SELECT order_id FROM orders WHERE idempotency_key = ?", Long.class, key);
        }

        @AfterEach
        void removeOrders() {
            jdbc.update("DELETE FROM orders WHERE idempotency_key LIKE 'sweep-%'");
        }

        @Test
        @DisplayName("A filled order with no notification is notified once, under the shared outcome key")
        void notifiesOnce() {
            seedPreference(1L, "PUSH", null);
            long orderId = fill("sweep-1");

            sweeper().sweep();
            sweeper().sweep();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE event_id = ?", Integer.class,
                    "order-" + orderId + "-ORDER_FILLED")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM notification WHERE event_id = ?", String.class,
                    "order-" + orderId + "-ORDER_FILLED")).isEqualTo("SENT");
        }

        @Test
        @DisplayName("A late Kafka event for an order the sweep already notified books nothing more")
        void lateKafkaEventIsTheSameNotification() {
            long orderId = fill("sweep-2");
            sweeper().sweep();

            java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("orderId", orderId);
            payload.put("accountId", 1);
            payload.put("symbol", "AAPL");
            notificationService.recordOrderOutcome(com.trading.tradeapi.notifications.service.TradeEventPayload
                    .asOutcome("kafka-uuid-123", "ORDER_FILLED", payload));

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE body LIKE ?", Integer.class,
                    "Order " + orderId + " %")).isEqualTo(1);
        }

        @Test
        @DisplayName("An order already notified the old way (a random event id) is left alone")
        void earlierNotificationIsRespected() {
            long orderId = fill("sweep-3");
            notificationService.recordOrderOutcome(new OrderOutcome("legacy-uuid", "ORDER_FILLED", 1L, "AAPL",
                    orderId, "BUY", 3L, new BigDecimal("191.5"), null));

            sweeper().sweep();

            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM notification WHERE body LIKE ?", Integer.class,
                    "Order " + orderId + " %")).isEqualTo(1);
        }
    }
}

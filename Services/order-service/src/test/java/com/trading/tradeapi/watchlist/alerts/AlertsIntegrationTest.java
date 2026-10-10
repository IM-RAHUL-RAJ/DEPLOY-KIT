package com.trading.tradeapi.watchlist.alerts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.TestTokenFactory;
import com.trading.tradeapi.watchlist.alerts.dto.PriceAlertDto;
import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.notifications.dto.NotificationDto;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
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

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The watchlists price-alerts module over HTTP with the real filter, the real service, the real
 * mapper and — for the crossing — the real notifications seam. Seed data: account 1 and 2 belong
 * to different users; instruments AAPL and GOOGL exist. Its own database, like the other modules.
 *
 * <p>The Kafka consumer is switched off in {@code test/application.yml}; the listener calls
 * {@link AlertService#evaluate} for each quote, which is exactly what these tests drive, and the
 * decode half is covered by {@link com.trading.tradeapi.watchlist.alerts.kafka.MarketDataConsumerDecodeTest}.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:alerts-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@DisplayName("Watchlists price alerts module")
class AlertsIntegrationTest {
    private static final String BASE = "/api/v1/watchlist/alerts";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AlertService alertService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void clearModuleTables() {
        jdbc.update("DELETE FROM price_alert");
        jdbc.update("DELETE FROM notification");
        jdbc.update("DELETE FROM customer_preference");
    }

    private void seedPreference(long accountId, String channel, String contact) {
        jdbc.update("INSERT INTO customer_preference (account_id, alert_channel, alert_contact) VALUES (?, ?, ?)",
                accountId, channel, contact);
    }

    private void seed(String accountId, String symbol, String direction, BigDecimal threshold, String status) {
        jdbc.update("INSERT INTO price_alert (account_id, symbol, direction, threshold, status) VALUES (?, ?, ?, ?, ?)",
                accountId, symbol, direction, threshold, status);
    }

    private long seedAndReturnId(String accountId, String symbol, String direction, BigDecimal threshold) {
        jdbc.update("INSERT INTO price_alert (account_id, symbol, direction, threshold, status) VALUES (?, ?, ?, ?, 'ACTIVE')",
                accountId, symbol, direction, threshold);
        Long id = jdbc.queryForObject(
                "SELECT price_alert_id FROM price_alert WHERE account_id = ? AND symbol = ? AND direction = ? AND threshold = ?",
                Long.class, accountId, symbol, direction, threshold);
        return id == null ? 0L : id;
    }

    private String alertStatus(long alertId) {
        String status = jdbc.queryForObject(
                "SELECT status FROM price_alert WHERE price_alert_id = ?", String.class, alertId);
        return status == null ? "" : status;
    }

    private int countAlerts() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM price_alert", Integer.class);
        return n == null ? 0 : n;
    }

    private int countNotifications() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        return n == null ? 0 : n;
    }

    private String tokenFor(long accountId) {
        return TestTokenFactory.createToken(accountId, jwtSecret, 60_000L);
    }

    private String adminToken() {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .issuer("auth-service")
                .subject("00000000-0000-0000-0000-000000000002")
                .claims(Map.of("roles", List.of("ADMIN")))
                .issuedAt(new Date(now))
                .expiration(new Date(now + 60_000L))
                .signWith(key)
                .compact();
    }

    private List<PriceAlertDto> listFor(long accountId) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE)
                        .header("Authorization", "Bearer " + tokenFor(accountId)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
    }

    private List<NotificationDto> notificationsOf(long accountId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/notifications/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                });
    }

    @Nested
    @DisplayName("The probes every route in this sprint has to survive")
    class TheProbesEveryRouteInThisSprintHasToSurvive {

        @Test
        @DisplayName("Refused with no token")
        void noTokenIsRefused() throws Exception {
            mockMvc.perform(get(BASE))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        }

        @Test
        @DisplayName("An administrator, who has no account, is forbidden rather than signed out")
        void adminWithoutAnAccountIsForbidden() throws Exception {
            mockMvc.perform(get(BASE).header("Authorization", "Bearer " + adminToken()))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ACC-403"));
        }
    }

    @Nested
    @DisplayName("Arming and listing")
    class ArmingAndListing {

        @Test
        @DisplayName("Arming via the API stores an ACTIVE alert that the list returns")
        void createAndList() throws Exception {
            mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType("application/json")
                            .content("""
                                    {"symbol":"AAPL","direction":"ABOVE","threshold":240.00}
                                    """))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.symbol").value("AAPL"))
                    .andExpect(jsonPath("$.status").value("ACTIVE"));

            List<PriceAlertDto> alerts = listFor(1L);
            assertThat(alerts).hasSize(1);
            assertThat(alerts.get(0).threshold()).isEqualByComparingTo("240.00000000");
            assertThat(alerts.get(0).symbol()).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("A symbol the catalogue does not know is refused with VAL-422")
        void unknownSymbolIsRefused() throws Exception {
            mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType("application/json")
                            .content("""
                                    {"symbol":"ZZZZ","direction":"ABOVE","threshold":10.00}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("VAL-422"));
        }

        @Test
        @DisplayName("An active alert at the 20 cap is refused; cancelled ones do not count")
        void activeCapApplies() throws Exception {
            for (int i = 0; i < 20; i++) {
                seed("1", "SYM" + i, "ABOVE", new BigDecimal("1.00"), "ACTIVE");
            }
            mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType("application/json")
                            .content("""
                                    {"symbol":"AAPL","direction":"ABOVE","threshold":240.00}
                                    """))
                    .andExpect(status().isUnprocessableEntity())
                    .andExpect(jsonPath("$.errorCode").value("VAL-422"));

            jdbc.update("UPDATE price_alert SET status = 'CANCELLED'");
            mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType("application/json")
                            .content("""
                                    {"symbol":"AAPL","direction":"ABOVE","threshold":240.00}
                                    """))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("Arming the identical active alert again returns the same one, no duplicate")
        void duplicateReturnsTheSameAlert() throws Exception {
            MvcResult first = mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType("application/json")
                            .content("""
                                    {"symbol":"AAPL","direction":"ABOVE","threshold":240.00}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn();
            PriceAlertDto firstDto = objectMapper.readValue(first.getResponse().getContentAsString(), PriceAlertDto.class);
            MvcResult second = mockMvc.perform(post(BASE)
                            .header("Authorization", "Bearer " + tokenFor(1L))
                            .contentType("application/json")
                            .content("""
                                    {"symbol":"AAPL","direction":"ABOVE","threshold":240.00}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn();
            PriceAlertDto secondDto = objectMapper.readValue(second.getResponse().getContentAsString(), PriceAlertDto.class);

            assertThat(countAlerts()).isEqualTo(1);
            assertThat(secondDto.alertId()).isEqualTo(firstDto.alertId());
            assertThat(secondDto.status()).isEqualTo("ACTIVE");
        }
    }

    @Nested
    @DisplayName("Cancelling")
    class Cancelling {

        @Test
        @DisplayName("Cancelling marks the alert CANCELLED and is idempotent")
        void cancelIsIdempotent() throws Exception {
            long id = seedAndReturnId("1", "AAPL", "ABOVE", new BigDecimal("240.00"));

            mockMvc.perform(delete(BASE + "/" + id).header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            mockMvc.perform(delete(BASE + "/" + id).header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
        }

        @Test
        @DisplayName("Another account's alert reads as not found, never as a hint")
        void foreignAlertIsNotFound() throws Exception {
            long id = seedAndReturnId("2", "AAPL", "ABOVE", new BigDecimal("240.00"));

            mockMvc.perform(delete(BASE + "/" + id).header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ACC-404"));
        }
    }

    @Nested
    @DisplayName("The crossing and the notifications seam")
    class TheCrossingAndTheNotificationsSeam {

        @Test
        @DisplayName("A crossed threshold fires once and books a SENT PRICE_ALERT notification")
        void crossingFiresOnceAndDeliversThroughTheSeam() throws Exception {
            long id = seedAndReturnId("1", "AAPL", "ABOVE", new BigDecimal("240.00"));
            seedPreference(1L, "PUSH", null);

            alertService.evaluate("AAPL", new BigDecimal("241.00"), Instant.parse("2026-10-19T14:00:00Z"));

            assertThat(alertStatus(id)).isEqualTo("TRIGGERED");
            List<NotificationDto> inbox = notificationsOf(1L, tokenFor(1L));
            assertThat(inbox).hasSize(1);
            assertThat(inbox.get(0).category()).isEqualTo("PRICE_ALERT");
            assertThat(inbox.get(0).channel()).isEqualTo("PUSH");
            assertThat(inbox.get(0).status()).isEqualTo("SENT");
            assertThat(inbox.get(0).symbol()).isEqualTo("AAPL");
        }

        @Test
        @DisplayName("Replaying the same market-data message cannot re-fire or double-notify")
        void replayCannotRefire() throws Exception {
            long id = seedAndReturnId("1", "GOOGL", "BELOW", new BigDecimal("170.00"));
            seedPreference(1L, "PUSH", null);

            alertService.evaluate("GOOGL", new BigDecimal("169.40"), Instant.parse("2026-10-19T14:03:22Z"));
            alertService.evaluate("GOOGL", new BigDecimal("169.40"), Instant.parse("2026-10-19T14:03:22Z"));

            assertThat(alertStatus(id)).isEqualTo("TRIGGERED");
            assertThat(countNotifications()).isEqualTo(1);
        }

        @Test
        @DisplayName("A quote that has stayed in range touches nothing")
        void inRangeQuoteTouchesNothing() throws Exception {
            long id = seedAndReturnId("1", "AAPL", "ABOVE", new BigDecimal("240.00"));

            alertService.evaluate("AAPL", new BigDecimal("239.50"), Instant.parse("2026-10-19T14:00:00Z"));

            assertThat(alertStatus(id)).isEqualTo("ACTIVE");
            assertThat(countNotifications()).isZero();
        }
    }
}
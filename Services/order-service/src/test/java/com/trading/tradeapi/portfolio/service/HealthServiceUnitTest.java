package com.trading.tradeapi.portfolio.service;

import com.trading.tradeapi.portfolio.dto.HealthResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The four probes, run without live infrastructure. Fauxnance is reported from the
 * feed status the market-data poller records, never by calling Fauxnance, because
 * every call (the usage check included) costs quota. The contract's shape is pinned:
 * always an answer, a degraded dependency sets {@code status} to degraded.
 */
class HealthServiceUnitTest {

    private static final String DEAD_KAFKA = "localhost:1";

    private static Map<String, Object> feed(Instant success, Instant failure, Integer daily, Integer used) {
        Map<String, Object> row = new HashMap<>();
        row.put("last_success_at", success == null ? null : Timestamp.from(success));
        row.put("last_failure_at", failure == null ? null : Timestamp.from(failure));
        row.put("daily_quota", daily);
        row.put("used_today", used);
        return row;
    }

    private static JdbcTemplate jdbcWithFeed(Map<String, Object> feed) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForMap(anyString())).thenReturn(feed);
        return jdbc;
    }

    private static HealthResponse.Dependency dependency(HealthResponse health, String name) {
        return health.dependencies().stream().filter(d -> d.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("every dependency is listed, and an offline broker degrades the status")
    void dependenciesAreListedAndAnOfflineBrokerDegradesTheStatus() {
        Instant now = Instant.now();
        HealthService service = new HealthService(jdbcWithFeed(feed(now, null, 2000, 120)), DEAD_KAFKA, 120);

        HealthResponse health = service.checkHealth();

        assertThat(health.status()).isEqualTo("degraded");
        assertThat(health.dependencies()).hasSize(4);
        assertThat(dependency(health, "postgres").status()).isEqualTo("ok");
        assertThat(dependency(health, "kafka").status()).isEqualTo("down");
        assertThat(dependency(health, "trade-api").status()).isEqualTo("ok");
    }

    @Test
    @DisplayName("a feed that reached Fauxnance recently is ok, with the quota the poller last read")
    void recentFeedIsOkWithQuota() {
        Instant now = Instant.now();
        HealthService service = new HealthService(jdbcWithFeed(feed(now, null, 2000, 124)), DEAD_KAFKA, 120);

        HealthResponse.Dependency fauxnance = dependency(service.checkHealth(), "fauxnance");

        assertThat(fauxnance.status()).isEqualTo("ok");
        assertThat(fauxnance.quotaRemaining()).isEqualTo(1876);
    }

    @Test
    @DisplayName("a feed whose last attempt failed is degraded")
    void failingFeedIsDegraded() {
        Instant now = Instant.now();
        HealthService service = new HealthService(
                jdbcWithFeed(feed(now.minus(10, ChronoUnit.MINUTES), now, 2000, 124)), DEAD_KAFKA, 120);

        assertThat(dependency(service.checkHealth(), "fauxnance").status()).isEqualTo("degraded");
    }

    @Test
    @DisplayName("a feed that has not reached Fauxnance for longer than the freshness window is degraded")
    void quietFeedIsDegraded() {
        Instant old = Instant.now().minus(5, ChronoUnit.HOURS);
        HealthService service = new HealthService(jdbcWithFeed(feed(old, null, 2000, 10)), DEAD_KAFKA, 120);

        assertThat(dependency(service.checkHealth(), "fauxnance").status()).isEqualTo("degraded");
    }

    @Test
    @DisplayName("an unreadable feed status degrades rather than failing the answer")
    void unreadableFeedDegrades() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        when(jdbc.queryForMap(anyString())).thenThrow(new DataAccessResourceFailureException("no table"));

        HealthResponse health = new HealthService(jdbc, DEAD_KAFKA, 120).checkHealth();

        assertThat(dependency(health, "fauxnance").status()).isEqualTo("degraded");
        assertThat(dependency(health, "fauxnance").quotaRemaining()).isNull();
    }

    @Test
    @DisplayName("a failing database is down, not silent")
    void aFailingDatabaseIsDownNotSilent() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException("db unavailable"));
        when(jdbc.queryForMap(anyString())).thenReturn(feed(Instant.now(), null, null, null));

        HealthResponse health = new HealthService(jdbc, DEAD_KAFKA, 120).checkHealth();

        assertThat(health.status()).isEqualTo("degraded");
        assertThat(dependency(health, "postgres").status()).isEqualTo("down");
    }

    @Test
    @DisplayName("the health check makes no Fauxnance call: it only reads the feed status")
    void healthNeverCallsFauxnance() {
        JdbcTemplate jdbc = jdbcWithFeed(feed(Instant.now(), null, 2000, 0));
        new HealthService(jdbc, DEAD_KAFKA, 120).checkHealth();
        // The only collaborator is the database; there is no HTTP client to call.
        org.mockito.Mockito.verify(jdbc).queryForObject("SELECT 1", Integer.class);
        org.mockito.Mockito.verify(jdbc).queryForMap(anyString());
        verifyNoMoreInteractions(jdbc);
    }
}

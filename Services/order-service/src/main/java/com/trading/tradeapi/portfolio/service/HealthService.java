package com.trading.tradeapi.portfolio.service;

import com.trading.tradeapi.portfolio.dto.HealthResponse;
import com.trading.tradeapi.portfolio.dto.HealthResponse.Dependency;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * The four probes behind the health endpoint. Each runs inside its own try/catch, so
 * one outage never blacks out the other probes, and the answer is always a 200: a
 * down dependency degrades {@code status} instead of failing the response, as the
 * contract's optional port monitoring expects. Kafka carries a hard two-second cap so
 * an unreachable broker cannot stall the response.
 *
 * <p>Fauxnance is reported from what the market-data poller recorded in
 * {@code market_feed_status}, not by calling it: every Fauxnance request, the usage
 * check included, costs quota, and a health endpoint is polled far more often than
 * the quota allows.
 */
@Service
public class HealthService {

    private static final Logger log = LoggerFactory.getLogger(HealthService.class);

    private final JdbcTemplate jdbcTemplate;
    private final String kafkaBootstrap;
    private final Duration feedFreshness;

    public HealthService(JdbcTemplate jdbcTemplate,
                         @Value("${spring.kafka.bootstrap-servers}") String kafkaBootstrap,
                         @Value("${market.health-fresh-minutes:120}") long feedFreshMinutes) {
        this.jdbcTemplate = jdbcTemplate;
        this.kafkaBootstrap = kafkaBootstrap;
        this.feedFreshness = Duration.ofMinutes(feedFreshMinutes);
    }

    public HealthResponse checkHealth() {
        List<Dependency> dependencies = new ArrayList<>();
        dependencies.add(postgres());
        dependencies.add(fauxnance());
        dependencies.add(kafka());
        dependencies.add(tradeApi());

        String status = dependencies.stream().allMatch(d -> "ok".equals(d.status())) ? "ok" : "degraded";
        return new HealthResponse(status, dependencies, Instant.now());
    }

    private Dependency postgres() {
        try {
            jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return Dependency.ok("postgres");
        } catch (RuntimeException e) {
            log.warn("Health: postgres down: {}", e.getMessage());
            return Dependency.down("postgres");
        }
    }

    /**
     * Healthy when the poller reached Fauxnance recently and its last attempt did not
     * fail; the quota is the poller's last hourly reading.
     */
    private Dependency fauxnance() {
        try {
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT last_success_at, last_failure_at, daily_quota, used_today"
                            + " FROM market_feed_status WHERE id = 1");
            Instant success = instant(row.get("last_success_at"));
            Instant failure = instant(row.get("last_failure_at"));
            boolean recent = success != null && success.isAfter(Instant.now().minus(feedFreshness));
            boolean failingNow = failure != null && (success == null || failure.isAfter(success));

            Integer quota = null;
            if (row.get("daily_quota") instanceof Number daily && row.get("used_today") instanceof Number used) {
                quota = Math.max(daily.intValue() - used.intValue(), 0);
            }
            if (!recent || failingNow) {
                return Dependency.degraded("fauxnance");
            }
            return quota == null ? Dependency.ok("fauxnance") : Dependency.withQuota("fauxnance", quota);
        } catch (RuntimeException e) {
            log.warn("Health: market-data feed status unreadable: {}", e.getMessage());
            return Dependency.degraded("fauxnance");
        }
    }

    private static Instant instant(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant();
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.toInstant();
        }
        return null;
    }

    private Dependency kafka() {
        Properties props = new Properties();
        props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrap);
        props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000);
        props.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 2000);
        try (AdminClient admin = AdminClient.create(props)) {
            admin.listTopics().names().get(2, TimeUnit.SECONDS);
            return Dependency.ok("kafka");
        } catch (Exception e) {
            log.warn("Health: kafka down: {}", e.getMessage());
            return Dependency.down("kafka");
        }
    }

    private Dependency tradeApi() {
        return Dependency.ok("trade-api");
    }
}

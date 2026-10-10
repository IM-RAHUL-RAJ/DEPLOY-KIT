package com.trading.tradeapi.advice;

import com.trading.tradeapi.TestTokenFactory;
import com.trading.tradeapi.advice.service.SignalEngineTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Trade advice over HTTP with the real filter, service and market-data cache. Seed data:
 * accounts 1 and 2 belong to different users; account 1 holds AAPL. Candles are written
 * straight into the cache the executor's poller fills.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:advice-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@DisplayName("Trade advice module")
class AdviceIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @BeforeEach
    void seedCandles() {
        jdbc.update("DELETE FROM market_candles");
        jdbc.update("DELETE FROM market_quote");
        LocalDate day = LocalDate.now().minusDays(61);
        List<Double> closes = SignalEngineTest.zigzag(61, 2, 1);
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < closes.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"date\":\"").append(day.plusDays(i)).append("\",\"close\":").append(closes.get(i))
                    .append(",\"adjclose\":").append(closes.get(i)).append('}');
        }
        json.append(']');
        jdbc.update("INSERT INTO market_candles (symbol, currency, candles, fetched_on) VALUES ('AAPL', 'USD', ?, CURRENT_DATE)",
                json.toString());
        // A fresh polled quote, so nothing waits on the poller.
        jdbc.update("INSERT INTO market_quote (symbol, price, bid, ask, currency, market_state, stale, quote_as_of, fetched_at)"
                + " VALUES ('AAPL', 131, 130.9, 131.1, 'USD', 'open', false, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @Test
    @DisplayName("Refused with no token")
    void noTokenIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/advice/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Another account's advice is refused with ACC-403")
    void otherAccountIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/advice/2").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
        mockMvc.perform(get("/api/v1/advice/2/AAPL").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    @DisplayName("A held symbol gets a signal with reasoning, the holding and the methodology inputs")
    void signalForAHolding() throws Exception {
        mockMvc.perform(get("/api/v1/advice/1/AAPL").header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.symbol").value("AAPL"))
                .andExpect(jsonPath("$.status").value("SIGNAL"))
                .andExpect(jsonPath("$.action").value("BUY"))
                .andExpect(jsonPath("$.reasons.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.holding.quantity").value(100))
                .andExpect(jsonPath("$.indicators.dailyCloses").value(61));
    }

    private String tokenFor(long accountId) {
        return TestTokenFactory.createToken(accountId, jwtSecret, 60_000L);
    }
}

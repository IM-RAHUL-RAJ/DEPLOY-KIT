package com.trading.tradeapi.portfolio;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.TestTokenFactory;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import static com.trading.tradeapi.marketdata.TestQuotes.*;
import com.trading.tradeapi.portfolio.dto.PnlResponseDto;
import com.trading.tradeapi.portfolio.dto.PortfolioSummaryDto;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The portfolio module end to end: real filter, real service, real mappers, its own database.
 * The market-data service is mocked because no poller runs in the build; everything the
 * service needs to decide about stale versus priced comes from the mock's answers. Seed data:
 * account 1 in USD with cash 10,000, 100 AAPL at 150 and one filled BUY of 10 at 150.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:portfolio-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE",
        "spring.kafka.bootstrap-servers=localhost:1"
})
@AutoConfigureMockMvc
@DisplayName("Portfolio and P&L module")
class PortfolioIntegrationTest {
    private static final String BASE = "/api/v1/portfolio";
    private static final BigDecimal AAPL_LAST = new BigDecimal("160.00");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${jwt.secret}")
    private String jwtSecret;

    @MockitoBean
    private MarketDataService fauxnanceClient;

    @BeforeEach
    void resetState() {
        jdbc.update("DELETE FROM orders");
        jdbc.update("DELETE FROM holding");
        jdbc.update("UPDATE instrument SET quote_currency = 'USD' WHERE symbol = 'GOOGL'");

        jdbc.update("INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date) "
                + "VALUES (1, 'AAPL', 100.00000000, 150.00000000, CURRENT_DATE)");

        seedOrder(1, "BUY", "10.00000000", "150.00000000", "FILLED",
                Instant.now().minusSeconds(60L * 24 * 60 * 60), "portfolio-baseline-1");

        when(fauxnanceClient.quotes(anyCollection()))
                .thenReturn(Map.of("AAPL", q("AAPL", AAPL_LAST, "USD", false)));
    }

    private void seedOrder(long accountId, String side, String quantity, String price, String status,
                           Instant executedOn, String idempotencyKey) {
        jdbc.update("INSERT INTO orders (account_id, instrument_id, ticker, side, quantity, price, status, "
                        + "received_at, idempotency_key, executed_price, executed_on) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                accountId, 1, "AAPL", side, new BigDecimal(quantity), new BigDecimal(price), status,
                java.sql.Timestamp.from(executedOn), idempotencyKey,
                "FILLED".equals(status) ? new BigDecimal(price) : null,
                "FILLED".equals(status) ? java.sql.Timestamp.from(executedOn) : null);
    }

    private String tokenFor(long accountId) {
        return TestTokenFactory.createToken(accountId, jwtSecret, 60_000L);
    }

    @Nested
    @DisplayName("The probes")
    class TheProbes {

        @Test
        @DisplayName("Refused with no token")
        void noTokenIsRefused() throws Exception {
            mockMvc.perform(get(BASE + "/1"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
        }

        @Test
        @DisplayName("A different account is forbidden, not found")
        void crossAccountIsForbidden() throws Exception {
            mockMvc.perform(get(BASE + "/1").header("Authorization", "Bearer " + tokenFor(2L)))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode").value("ACC-403"));
        }

        @Test
        @DisplayName("A foreign account that does exist on none of the caller's tokens is not leaked")
        void missingAccountIsNotFound() throws Exception {
            mockMvc.perform(get(BASE + "/999").header("Authorization", "Bearer " + tokenFor(999L)))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode").value("ACC-404"));
        }
    }

    @Nested
    @DisplayName("Summary")
    class Summary {

        @Test
        @DisplayName("Summary prices the holding and totals in the ledger currency")
        void summaryPricesThePortfolio() throws Exception {
            MvcResult result = mockMvc.perform(get(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PortfolioSummaryDto summary = objectMapper.readValue(result.getResponse().getContentAsString(),
                    PortfolioSummaryDto.class);

            assertThat(summary.baseCurrency()).isEqualTo("USD");
            assertThat(summary.cashBalance()).isEqualByComparingTo("10000.00");
            assertThat(summary.costBasis()).isEqualByComparingTo("15000.00");
            assertThat(summary.marketValue()).isEqualByComparingTo("16000.00");
            assertThat(summary.unrealisedPnl()).isEqualByComparingTo("1000.00");
            assertThat(summary.realisedPnl()).isEqualByComparingTo("0");
            assertThat(summary.totalValue()).isEqualByComparingTo("26000.00");
            assertThat(summary.positionCount()).isEqualTo(1);
            assertThat(summary.partial()).isFalse();
        }

        @Test
        @DisplayName("A filled sell books its realised P&L against the running average")
        void realisedFromaSellFillsThrough() throws Exception {
            seedOrder(1, "SELL", "5.00000000", "180.00000000", "FILLED", Instant.now(), "portfolio-sell-1");

            MvcResult result = mockMvc.perform(get(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PortfolioSummaryDto summary = objectMapper.readValue(result.getResponse().getContentAsString(),
                    PortfolioSummaryDto.class);

            assertThat(summary.realisedPnl()).isEqualByComparingTo("150");
        }
    }

    @Nested
    @DisplayName("Positions")
    class Positions {

        @Test
        @DisplayName("Positions are priced native rows with quote freshness")
        void positionsArePricedNativeRows() throws Exception {
            MvcResult result = mockMvc.perform(get(BASE + "/1/positions")
                            .header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PricedPositionDto[] positions = objectMapper.readValue(result.getResponse().getContentAsString(),
                    PricedPositionDto[].class);

            assertThat(positions).hasSize(1);
            assertThat(positions[0].symbol()).isEqualTo("AAPL");
            assertThat(positions[0].quantity()).isEqualTo(100);
            assertThat(positions[0].lastPrice()).isEqualByComparingTo("160.00");
            assertThat(positions[0].marketValue()).isEqualByComparingTo("16000.00");
            assertThat(positions[0].currency()).isEqualTo("USD");
            assertThat(positions[0].stale()).isFalse();
        }

        @Test
        @DisplayName("The symbol filter narrows the answer")
        void positionsHonourTheSymbolFilter() throws Exception {
            mockMvc.perform(get(BASE + "/1/positions?symbol=AAPL")
                            .header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].symbol").value("AAPL"));
        }
    }

    @Nested
    @DisplayName("P&L")
    class PL {

        @Test
        @DisplayName("P&L returns realised, unrealised and per-symbol figures")
        void pnlCombinesBothFigures() throws Exception {
            seedOrder(1, "SELL", "5.00000000", "180.00000000", "FILLED", Instant.now(), "portfolio-sell-1");

            MvcResult result = mockMvc.perform(get(BASE + "/1/pnl").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PnlResponseDto pnl = objectMapper.readValue(result.getResponse().getContentAsString(), PnlResponseDto.class);

            assertThat(pnl.realisedPnl()).isEqualByComparingTo("150");
            assertThat(pnl.unrealisedPnl()).isEqualByComparingTo("1000");
            assertThat(pnl.totalPnl()).isEqualByComparingTo("1150");
            assertThat(pnl.bySymbol()).hasSize(1);
            assertThat(pnl.bySymbol().get(0).symbol()).isEqualTo("AAPL");
            assertThat(pnl.bySymbol().get(0).realisedPnl()).isEqualByComparingTo("150");
            assertThat(pnl.bySymbol().get(0).unrealisedPnl()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("The from/to window bounds only which sells are counted")
        void pnlWindowBoundsTheReportedSells() throws Exception {
            Instant aMonthAgo = Instant.now().minusSeconds(30L * 24 * 60 * 60);
            seedOrder(1, "SELL", "5.00000000", "190.00000000", "FILLED", aMonthAgo, "portfolio-sell-old");

            MvcResult bounded = mockMvc.perform(get(BASE + "/1/pnl?from=2020-01-01&to=2020-12-31")
                            .header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PnlResponseDto boundedDto = objectMapper.readValue(bounded.getResponse().getContentAsString(),
                    PnlResponseDto.class);
            assertThat(boundedDto.realisedPnl()).isEqualByComparingTo("0");

            MvcResult all = mockMvc.perform(get(BASE + "/1/pnl").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PnlResponseDto allDto = objectMapper.readValue(all.getResponse().getContentAsString(), PnlResponseDto.class);
            assertThat(allDto.realisedPnl()).isEqualByComparingTo("200");
        }
    }

    @Nested
    @DisplayName("Edge behaviour")
    class EdgeBehaviour {

        @Test
        @DisplayName("Holdings without any usable quote are answered MKT-503, not nulls")
        void noQuoteAtAllIsMkt503() throws Exception {
            when(fauxnanceClient.quotes(anyCollection())).thenReturn(Map.of());

            mockMvc.perform(get(BASE + "/1/positions").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.errorCode").value("MKT-503"));
        }

        @Test
        @DisplayName("A priced holding with no FX rate is excluded from totals and flags partial")
        void unstraddableCurrencyIsPartial() throws Exception {
            jdbc.update("INSERT INTO holding (account_id, ticker, quantity, average_price, as_of_date) "
                    + "VALUES (1, 'GOOGL', 2.00000000, 100.00000000, CURRENT_DATE)");
            jdbc.update("UPDATE instrument SET quote_currency = 'XXX' WHERE symbol = 'GOOGL'");
            when(fauxnanceClient.quotes(anyCollection())).thenReturn(Map.of(
                    "AAPL", q("AAPL", AAPL_LAST, "USD", false),
                    "GOOGL", q("GOOGL", new BigDecimal("105.00"), "XXX", false)));

            MvcResult result = mockMvc.perform(get(BASE + "/1").header("Authorization", "Bearer " + tokenFor(1L)))
                    .andExpect(status().isOk())
                    .andReturn();
            PortfolioSummaryDto summary = objectMapper.readValue(result.getResponse().getContentAsString(),
                    PortfolioSummaryDto.class);

            assertThat(summary.partial()).isTrue();
            assertThat(summary.positionCount()).isEqualTo(2);
            // 210 of XXX has no rate into USD, so only AAPL's 16000 lands in the totals.
            assertThat(summary.marketValue()).isEqualByComparingTo("16000.00");
        }
    }

    @Nested
    @DisplayName("Health")
    class Health {

        @Test
        @DisplayName("Health always answers 200 and reports each dependency")
        void healthReportsDependencies() throws Exception {
            mockMvc.perform(get("/health"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("degraded"))
                    .andExpect(jsonPath("$.dependencies.length()").value(4))
                    .andExpect(jsonPath("$.dependencies[?(@.name == 'postgres')].status").value("ok"))
                    .andExpect(jsonPath("$.dependencies[?(@.name == 'trade-api')].status").value("ok"))
                    .andExpect(jsonPath("$.dependencies[?(@.name == 'fauxnance')].status").value("degraded"))
                    .andExpect(jsonPath("$.dependencies[?(@.name == 'kafka')].status").value("down"));
        }
    }
}
package com.trading.tradeapi.portfolio.controller;

import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.portfolio.dto.PnlResponseDto;
import com.trading.tradeapi.portfolio.dto.PortfolioSummaryDto;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.portfolio.dto.SymbolPnlDto;
import com.trading.tradeapi.portfolio.service.PortfolioService;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The portfolio routes in the project's slice shape: the real controller and error handler,
 * everything beneath the service mocked, the audit of the account claim happening in the
 * controller exactly as in the notifications module.
 */
@WebMvcTest(value = PortfolioController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import(com.trading.tradeapi.exception.GlobalExceptionHandler.class)
class PortfolioControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PortfolioService portfolioService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // Mappers registered by the application's mapper scan — mocked so the slice starts
    // without a datasource, exactly as the other module slices do.
    @MockitoBean
    private com.trading.tradeapi.mapper.AccountMapper accountMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.UserMapper userMapper;
    @MockitoBean
    private com.trading.tradeapi.watchlist.mapper.WatchlistMapper watchlistMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.InstrumentMapper instrumentMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.OrderMapper orderMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.AdminMapper adminMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.HoldingMapper holdingMapper;
    @MockitoBean
    private com.trading.tradeapi.preferences.mapper.PreferencesMapper preferencesMapper;
    @MockitoBean
    private com.trading.tradeapi.notifications.mapper.NotificationMapper notificationMapper;
    @MockitoBean
    private com.trading.tradeapi.watchlist.alerts.mapper.PriceAlertMapper priceAlertMapper;
    @MockitoBean
    private com.trading.tradeapi.portfolio.mapper.PortfolioMapper portfolioMapper;

    @BeforeEach
    void letRequestsReachTheController() throws Exception {
        doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());
    }

    private static PortfolioSummaryDto summary() {
        return new PortfolioSummaryDto(1L, "USD", new BigDecimal("10000.00"),
                new BigDecimal("16000.00"), new BigDecimal("15000.00"),
                new BigDecimal("1000.00"), new BigDecimal("6.67"),
                new BigDecimal("0"), new BigDecimal("26000.00"),
                1, false, Instant.parse("2026-10-19T14:05:11Z"));
    }

    private static PricedPositionDto position() {
        return new PricedPositionDto(1L, "AAPL", 100, new BigDecimal("150.00"),
                new BigDecimal("15000.00"), new BigDecimal("160.00"),
                new BigDecimal("16000.00"), new BigDecimal("1000.00"),
                new BigDecimal("6.67"), "USD", Instant.parse("2026-10-19T14:05:11Z"), false);
    }

    @Test
    void summaryForTheOwnAccount() throws Exception {
        when(portfolioService.summary(1L)).thenReturn(summary());

        mockMvc.perform(get("/api/v1/portfolio/1").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.baseCurrency").value("USD"))
                .andExpect(jsonPath("$.marketValue").value(16000.00))
                .andExpect(jsonPath("$.cashBalance").value(10000.00))
                .andExpect(jsonPath("$.totalValue").value(26000.00))
                .andExpect(jsonPath("$.partial").value(false));
    }

    @Test
    void crossAccountIsForbiddenNotNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/1").requestAttr("authenticatedAccountId", 2L))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void reachingTheRouteWithoutAnAccountClaimIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/portfolio/1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    @Test
    void positionsForTheOwnAccount() throws Exception {
        when(portfolioService.positions(1L, null)).thenReturn(List.of(position()));

        mockMvc.perform(get("/api/v1/portfolio/1/positions").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"))
                .andExpect(jsonPath("$[0].lastPrice").value(160.00))
                .andExpect(jsonPath("$[0].stale").value(false));
    }

    @Test
    void positionsHonourTheSymbolFilter() throws Exception {
        when(portfolioService.positions(1L, "AAPL")).thenReturn(List.of(position()));

        mockMvc.perform(get("/api/v1/portfolio/1/positions?symbol=AAPL")
                        .requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("AAPL"));
    }

    @Test
    void pnlForTheOwnAccountWithAWindow() throws Exception {
        PnlResponseDto pnl = new PnlResponseDto(1L, "USD",
                LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-20"),
                new BigDecimal("-40"), new BigDecimal("200"), new BigDecimal("160"),
                List.of(new SymbolPnlDto("AAPL", new BigDecimal("-40"),
                        new BigDecimal("200"), new BigDecimal("160"))),
                Instant.parse("2026-10-19T14:05:11Z"));
        when(portfolioService.pnl(1L, LocalDate.parse("2026-01-10"), LocalDate.parse("2026-01-20")))
                .thenReturn(pnl);

        mockMvc.perform(get("/api/v1/portfolio/1/pnl?from=2026-01-10&to=2026-01-20")
                        .requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.realisedPnl").value(-40))
                .andExpect(jsonPath("$.unrealisedPnl").value(200))
                .andExpect(jsonPath("$.bySymbol[0].symbol").value("AAPL"));
    }

    @Test
    void aMarketPricingFailureSurfacesAsMkt503() throws Exception {
        when(portfolioService.summary(1L))
                .thenThrow(new DomainException("MKT-503",
                        "No live market quote is available for any holding; pricing cannot proceed",
                        HttpStatus.SERVICE_UNAVAILABLE));

        mockMvc.perform(get("/api/v1/portfolio/1").requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.errorCode").value("MKT-503"));
    }
}
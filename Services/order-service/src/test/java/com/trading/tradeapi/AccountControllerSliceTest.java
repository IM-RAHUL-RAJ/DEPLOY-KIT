package com.trading.tradeapi;

import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.controller.AccountController;
import com.trading.tradeapi.dto.AccountResponseDto;
import com.trading.tradeapi.dto.BalanceResponseDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.AccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;

@WebMvcTest(value = AccountController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import({GlobalExceptionHandler.class})
public class AccountControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;

    // Mappers registered by @MapperScan on TradeApiApplication — must be mocked
    // so the @WebMvcTest context can start without a real datasource / SqlSessionFactory
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
    private com.trading.tradeapi.mapper.HoldingMapper holdingMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.AdminMapper adminMapper;

    // Factories must be mocked to prevent Spring from trying to auto-wire them


    @org.junit.jupiter.api.BeforeEach
    public void setup() throws Exception {
        // The mocked filter must delegate to the chain so requests reach the controller.
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    public void getAccountSuccessReturns200AndAccountResponse() throws Exception {
        AccountResponseDto acc = new AccountResponseDto(
                1L, "ACC-000001", "Priya Menon", new BigDecimal("24500.75"), "USD", AccountStatus.ACTIVE, 7L, Instant.now()
        );
        when(accountService.getAccount(1L)).thenReturn(acc);

        mockMvc.perform(get("/api/v1/accounts/1")
                        .requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.accountId").value("ACC-000001"))
                .andExpect(jsonPath("$.holderName").value("Priya Menon"))
                .andExpect(jsonPath("$.cashBalance").value(24500.75))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(7));
    }

    @Test
    public void getAccountNotFoundReturns404Acc404() throws Exception {
        when(accountService.getAccount(999L)).thenThrow(new AccountNotFoundException());

        mockMvc.perform(get("/api/v1/accounts/999")
                        .requestAttr("authenticatedAccountId", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACC-404"))
                .andExpect(jsonPath("$.message").value("Account not found"));
    }

    @Test
    public void getBalanceSuccessReturns200AndBalanceResponse() throws Exception {
        BalanceResponseDto bal = new BalanceResponseDto(1L, new BigDecimal("24500.75"), "USD", Instant.now());
        when(accountService.getBalance(1L)).thenReturn(bal);

        mockMvc.perform(get("/api/v1/accounts/1/balance")
                        .requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(1))
                .andExpect(jsonPath("$.cashBalance").value(24500.75))
                .andExpect(jsonPath("$.currency").value("USD"));
    }

    @Test
    public void getPositionsSuccessReturns200AndPositionsList() throws Exception {
        PositionResponseDto pos1 = new PositionResponseDto(1L, "ACME", 100L, new BigDecimal("25.50"));
        PositionResponseDto pos2 = new PositionResponseDto(1L, "INFY.NS", 40L, new BigDecimal("1580.25"));
        when(accountService.getHoldings(1L)).thenReturn(List.of(pos1, pos2));

        mockMvc.perform(get("/api/v1/accounts/1/positions")
                        .requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].symbol").value("ACME"))
                .andExpect(jsonPath("$[0].quantity").value(100))
                .andExpect(jsonPath("$[1].symbol").value("INFY.NS"))
                .andExpect(jsonPath("$[1].quantity").value(40));
    }
}

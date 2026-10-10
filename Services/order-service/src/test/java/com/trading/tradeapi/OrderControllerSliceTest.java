package com.trading.tradeapi;

import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.exception.InsufficientHoldingsException;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.controller.OrderController;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.OrderService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(value = OrderController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import({GlobalExceptionHandler.class})
public class OrderControllerSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private OrderService orderService;

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
    private com.trading.tradeapi.mapper.AdminMapper adminMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.HoldingMapper holdingMapper;


    private PlaceOrderRequestDto validRequest;

    @BeforeEach
    public void setup() throws Exception {
        // The mocked filter must delegate to the chain so requests reach the controller.
        // The test sets 'authenticatedAccountId' via requestAttr() to simulate a valid token.
        org.mockito.Mockito.doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );

        validRequest = new PlaceOrderRequestDto(
                1L, "ACME", OrderSide.BUY, 100L, new BigDecimal("25.50"), OrderType.LIMIT, "6f2b1c2a-6a1e-4a4f-9c0d-2f7a1b3c4d5e"
        );
    }

    @Test
    public void placeOrderFieldValidationReturns422Val422() throws Exception {
        PlaceOrderRequestDto invalidReq = new PlaceOrderRequestDto(
                1L, "", OrderSide.BUY, 0L, new BigDecimal("-5.00"), OrderType.LIMIT, "short"
        );

        mockMvc.perform(post("/api/v1/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidReq))
                        .requestAttr("authenticatedAccountId", 1L))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"))
                // Validation message contains detailed field errors from @Valid
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    public void cancelOrderNotFoundReturns404() throws Exception {
        when(orderService.cancelOrder(eq("ORD-6"), any()))
                .thenThrow(new OrderNotFoundException());

        mockMvc.perform(delete("/api/v1/orders/ORD-6"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ORD-404"))
                .andExpect(jsonPath("$.message").value("Order not found"));
    }
}

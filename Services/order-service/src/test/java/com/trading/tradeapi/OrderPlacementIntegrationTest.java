package com.trading.tradeapi;

import com.trading.tradeapi.controller.OrderController;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.kafka.producer.OrderProducer;
import com.trading.tradeapi.kafka.producer.TradeEventProducer;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.InstrumentDiscoveryService;
import com.trading.tradeapi.service.MarketOrderPricer;
import com.trading.tradeapi.service.OrderService;
import com.trading.tradeapi.service.OrderValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Order placement through the web layer with the REAL OrderService and REAL
 * OrderValidator: HTTP in, admission rules, persistence call and Kafka publish
 * out. Only the database mappers, the instrument lookup and the producers are
 * replaced.
 *
 * <p>The headline rule: cash and holdings are the executor's decision. An order the
 * account cannot afford, or a sell of something it does not hold, is accepted as
 * NEW and published, never refused at the API with ORD-400 or ORD-409.
 */
@WebMvcTest(value = OrderController.class,
        excludeAutoConfiguration = {
                MybatisAutoConfiguration.class,
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class
        })
@Import({GlobalExceptionHandler.class, OrderService.class, OrderValidator.class})
class OrderPlacementIntegrationTest {

    private static final String ORDERS = "/api/v1/orders";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtAuthenticationFilter jwtAuthenticationFilter;
    @MockitoBean
    private MarketOrderPricer marketOrderPricer;
    @MockitoBean
    private InstrumentDiscoveryService instrumentDiscoveryService;
    @MockitoBean
    private OrderProducer orderProducer;
    @MockitoBean
    private TradeEventProducer tradeEventProducer;

    @MockitoBean
    private OrderMapper orderMapper;
    @MockitoBean
    private AccountMapper accountMapper;
    @MockitoBean
    private HoldingMapper holdingMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.UserMapper userMapper;
    @MockitoBean
    private com.trading.tradeapi.watchlist.mapper.WatchlistMapper watchlistMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.InstrumentMapper instrumentMapper;
    @MockitoBean
    private com.trading.tradeapi.mapper.AdminMapper adminMapper;

    @BeforeEach
    void setUp() throws Exception {
        doAnswer(inv -> {
            jakarta.servlet.FilterChain chain = inv.getArgument(2);
            chain.doFilter(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(jwtAuthenticationFilter).doFilter(any(), any(), any());

        // Limit orders pass through the pricer untouched, as in production.
        when(marketOrderPricer.price(any())).thenAnswer(inv -> inv.getArgument(0));
        when(instrumentDiscoveryService.resolveTradeableInstrument("AAPL"))
                .thenReturn(new InstrumentRecord(1L, "AAPL", "AAPL", "EQUITY", "USD", "ACTIVE", null));
        // The database assigns the id.
        doAnswer(inv -> {
            ReflectionTestUtils.setField(inv.getArgument(0, OrderRecord.class), "orderId", 42L);
            return 1;
        }).when(orderMapper).insertOrder(any(OrderRecord.class));
    }

    private void accountWithCash(String cash, AccountStatus status) {
        when(accountMapper.findById(101L)).thenReturn(
                new AccountRecord(101L, 1L, "Holder", "USD", new BigDecimal(cash), status, 1L, null));
    }

    private static String order(String side, long quantity, String price, String key) {
        return """
                {"accountId":101,"symbol":"AAPL","side":"%s","orderType":"LIMIT",
                 "quantity":%d,"price":%s,"idempotencyKey":"%s"}
                """.formatted(side, quantity, price, key);
    }

    @Test
    @DisplayName("a buy costing more than the cash balance is accepted as NEW and published")
    void buyBeyondCashIsAccepted() throws Exception {
        accountWithCash("10.00", AccountStatus.ACTIVE);

        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1000, "150.00", "key-buy-0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.orderId").exists());

        verify(orderMapper).insertOrder(any(OrderRecord.class));
        verify(orderProducer).publishOrderPlaced(eq(42L), eq(101L), eq("AAPL"), eq("BUY"), eq(1000L),
                any(BigDecimal.class), eq("key-buy-0001"), any());
    }

    @Test
    @DisplayName("a sell of a holding the account does not have is accepted as NEW, without reading holdings")
    void sellWithoutHoldingIsAccepted() throws Exception {
        accountWithCash("0.00", AccountStatus.ACTIVE);

        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("SELL", 999999, "0.01", "key-sell-0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));

        verifyNoInteractions(holdingMapper);
        verify(orderProducer).publishOrderPlaced(eq(42L), anyLong(), any(), eq("SELL"), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("a reused idempotency key is refused with ORD-409 and nothing is written")
    void duplicateKeyRefused() throws Exception {
        accountWithCash("1000.00", AccountStatus.ACTIVE);
        when(orderMapper.findByIdempotencyKey("key-dupe-0001")).thenReturn(new OrderRecord());

        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1, "10.00", "key-dupe-0001")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("ORD-409"));

        verify(orderMapper, never()).insertOrder(any());
        verifyNoInteractions(orderProducer);
    }

    @Test
    @DisplayName("a suspended account is refused with ACC-403")
    void suspendedAccountRefused() throws Exception {
        accountWithCash("1000.00", AccountStatus.SUSPENDED);

        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1, "10.00", "key-susp-0001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));

        verifyNoInteractions(orderProducer);
    }

    @Test
    @DisplayName("an unknown account is refused with ACC-404")
    void unknownAccountRefused() throws Exception {
        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1, "10.00", "key-none-0001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACC-404"));
    }

    @Test
    @DisplayName("an instrument that cannot be traded is refused with INS-404")
    void unknownInstrumentRefused() throws Exception {
        accountWithCash("1000.00", AccountStatus.ACTIVE);
        when(instrumentDiscoveryService.resolveTradeableInstrument("AAPL")).thenThrow(new InstrumentNotFoundException());

        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1, "10.00", "key-inst-0001")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("INS-404"));
    }

    @Test
    @DisplayName("an order for another account than the token's is refused with ACC-403")
    void otherAccountRefused() throws Exception {
        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 202L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1, "10.00", "key-othr-0001")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));

        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("bean validation refuses a zero quantity and a three-decimal price with VAL-422")
    void invalidBodyRefused() throws Exception {
        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 0, "10.001", "key-bad0-0001")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.errorCode").value("VAL-422"));

        verifyNoInteractions(orderMapper);
    }

    @Test
    @DisplayName("a failed publish does not undo an order that was recorded")
    void publishFailureKeepsOrder() throws Exception {
        accountWithCash("1000.00", AccountStatus.ACTIVE);
        doAnswer(inv -> { throw new RuntimeException("broker down"); })
                .when(orderProducer).publishOrderPlaced(anyLong(), anyLong(), any(), any(), anyLong(), any(), any(), any());

        mockMvc.perform(post(ORDERS).requestAttr("authenticatedAccountId", 101L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(order("BUY", 1, "10.00", "key-pub0-0001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEW"));
    }
}

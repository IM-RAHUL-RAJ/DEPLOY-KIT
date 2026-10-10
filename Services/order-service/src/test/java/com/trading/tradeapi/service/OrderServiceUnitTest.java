package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderStatus;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.exception.MarketDataUnavailableException;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.kafka.producer.OrderProducer;
import com.trading.tradeapi.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class OrderServiceUnitTest {
    @Mock
    private OrderMapper orderMapper;
    @Mock
    private OrderValidator orderValidator;
    @Mock
    private OrderProducer orderProducer;
    @Mock
    private MarketOrderPricer marketOrderPricer;
    @Mock
    private com.trading.tradeapi.kafka.producer.TradeEventProducer tradeEventProducer;

    @InjectMocks
    private OrderService orderService;

    private PlaceOrderRequestDto sampleRequest;

    @BeforeEach
    public void setup() {
        sampleRequest = new PlaceOrderRequestDto(
                101L,
                "AAPL",
                OrderSide.BUY,
                10L,
                new BigDecimal("150.00"),
                OrderType.LIMIT,
                "idempotency-key-123"
        );
        // Limit orders pass through the pricer untouched; market orders are the only
        // ones it reprices, so a plain pass-through is the honest default here.
        lenient().when(marketOrderPricer.price(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    @DisplayName("Placing")
    class Placing {

        @Test
        @DisplayName("placeOrder: Successful order placement returns NEW status and publishes to Kafka")
        public void placeOrderSuccess() {
            doAnswer(invocation -> {
                OrderRecord record = invocation.getArgument(0);
                record.setOrderId(1L);
                return 1;
            }).when(orderMapper).insertOrder(any(OrderRecord.class));

            OrderResponseDto response = orderService.placeOrder(sampleRequest);

            assertNotNull(response);
            assertEquals("ORD-1", response.orderId());
            assertEquals(OrderStatus.NEW, response.status());
            assertEquals("AAPL", response.symbol());
            assertEquals(OrderSide.BUY, response.side());

            verify(orderValidator, times(1)).validate(sampleRequest);
            verify(orderProducer, times(1)).publishOrderPlaced(
                    eq(1L), eq(101L), eq("AAPL"), eq("BUY"),
                    eq(10L), eq(new BigDecimal("150.00")), eq("idempotency-key-123"), any()
            );
        }

        @Test
        @DisplayName("placeOrder: Validator rejection propagates and the order is not inserted")
        public void placeOrderValidatorRejectsOrder() {
            doThrow(new DuplicateOrderException()).when(orderValidator).validate(sampleRequest);

            assertThrows(DuplicateOrderException.class, () -> orderService.placeOrder(sampleRequest));
            verify(orderMapper, never()).insertOrder(any());
        }

        @Test
        @DisplayName("placeOrder: Inactive account throws AccountNotActiveException")
        public void placeOrderInactiveAccount() {
            doThrow(new AccountNotActiveException()).when(orderValidator).validate(sampleRequest);

            assertThrows(AccountNotActiveException.class, () -> orderService.placeOrder(sampleRequest));
            verify(orderMapper, never()).insertOrder(any());
        }

        @Test
        @DisplayName("placeOrder: DB insert failure throws RuntimeException")
        public void placeOrderDbInsertFailure() {
            when(orderMapper.insertOrder(any(OrderRecord.class))).thenReturn(0);

            assertThrows(RuntimeException.class, () -> orderService.placeOrder(sampleRequest));
        }

        @Test
        @DisplayName("placeOrder: Kafka publish error is caught cleanly without failing order save")
        public void placeOrderKafkaErrorHandled() {
            doAnswer(inv -> {
                OrderRecord record = inv.getArgument(0);
                record.setOrderId(1L);
                return 1;
            }).when(orderMapper).insertOrder(any(OrderRecord.class));

            doThrow(new RuntimeException("Kafka down")).when(orderProducer).publishOrderPlaced(
                    any(), any(), any(), any(), any(), any(), any(), any()
            );

            OrderResponseDto response = orderService.placeOrder(sampleRequest);
            assertNotNull(response);
            assertEquals("ORD-1", response.orderId());
        }
    }

    @Nested
    @DisplayName("Market orders")
    class MarketOrders {

        @Test
        @DisplayName("placeOrder: Market order is priced server-side and the resolved price is what is persisted")
        public void placeOrderMarketOrderUsesServerPrice() {
            PlaceOrderRequestDto marketRequest = new PlaceOrderRequestDto(
                    101L, "RELIANCE.NS", OrderSide.BUY, 5L, null, OrderType.MARKET, "market-key-12345");

            PlaceOrderRequestDto priced = new PlaceOrderRequestDto(
                    101L, "RELIANCE.NS", OrderSide.BUY, 5L, new BigDecimal("132.50"),
                    OrderType.MARKET, "market-key-12345");

            when(marketOrderPricer.price(marketRequest)).thenReturn(priced);

            doAnswer(invocation -> {
                OrderRecord record = invocation.getArgument(0);
                record.setOrderId(7L);
                return 1;
            }).when(orderMapper).insertOrder(any(OrderRecord.class));

            OrderResponseDto response = orderService.placeOrder(marketRequest);

            assertEquals("ORD-7", response.orderId());
            assertEquals(new BigDecimal("132.50"), response.price());

            // The client never sees or sets the price: the persisted order carries the
            // server-resolved one, and the same number goes to the executor.
            ArgumentCaptor<OrderRecord> recordCaptor = ArgumentCaptor.forClass(OrderRecord.class);
            verify(orderMapper).insertOrder(recordCaptor.capture());
            assertEquals(new BigDecimal("132.50"), recordCaptor.getValue().getPrice());

            verify(orderProducer).publishOrderPlaced(
                    eq(7L), eq(101L), eq("RELIANCE.NS"), eq("BUY"),
                    eq(5L), eq(new BigDecimal("132.50")), eq("market-key-12345"), any());
        }

        @Test
        @DisplayName("placeOrder: An unpriceable market order is never persisted")
        public void placeOrderMarketOrderWithoutQuoteRejected() {
            PlaceOrderRequestDto marketRequest = new PlaceOrderRequestDto(
                    101L, "AAPL", OrderSide.BUY, 5L, null, OrderType.MARKET, "market-key-12345");

            when(marketOrderPricer.price(marketRequest)).thenThrow(new MarketDataUnavailableException("AAPL"));

            MarketDataUnavailableException thrown = assertThrows(
                    MarketDataUnavailableException.class, () -> orderService.placeOrder(marketRequest));

            assertEquals("MKT-503", thrown.getCode());
            verify(orderMapper, never()).insertOrder(any());
            verify(orderProducer, never()).publishOrderPlaced(any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("placeOrder: Insufficient funds for the resolved market price propagates")
        public void placeOrderMarketOrderInsufficientFunds() {
            PlaceOrderRequestDto marketRequest = new PlaceOrderRequestDto(
                    101L, "AAPL", OrderSide.BUY, 5000L, null, OrderType.MARKET, "market-key-12345");

            PlaceOrderRequestDto priced = new PlaceOrderRequestDto(
                    101L, "AAPL", OrderSide.BUY, 5000L, new BigDecimal("150.00"),
                    OrderType.MARKET, "market-key-12345");

            when(marketOrderPricer.price(marketRequest)).thenReturn(priced);
            doThrow(new InsufficientFundsException()).when(orderValidator).validate(priced);

            assertThrows(InsufficientFundsException.class, () -> orderService.placeOrder(marketRequest));
            verify(orderMapper, never()).insertOrder(any());
        }
    }

    @Nested
    @DisplayName("Cancelling")
    class Cancelling {

        @Test
        @DisplayName("cancelOrder: Successfully cancels NEW order")
        public void cancelOrderSuccess() {
            OrderRecord existing = new OrderRecord();
            existing.setStatus(OrderStatus.NEW);
            existing.setTicker("AAPL");
            existing.setSide(OrderSide.BUY);
            existing.setQuantity(new BigDecimal("10"));
            existing.setPrice(new BigDecimal("150.00"));

            when(orderMapper.findById("1")).thenReturn(existing);
            when(orderMapper.cancelOrder("1")).thenReturn(1);

            OrderResponseDto response = orderService.cancelOrder("1", null);
            assertNotNull(response);
            assertEquals(OrderStatus.CANCELLED, response.status());
        }

        @Test
        @DisplayName("cancelOrder: another account's order cannot be cancelled")
        public void cancelOrderOfAnotherAccountRefused() {
            OrderRecord theirs = new OrderRecord();
            theirs.setAccountId(202L);
            theirs.setStatus(OrderStatus.NEW);
            when(orderMapper.findById("1")).thenReturn(theirs);

            assertThrows(com.trading.tradeapi.exception.AccountNotActiveException.class,
                    () -> orderService.cancelOrder("1", 101L));
            verify(orderMapper, never()).cancelOrder(any());
        }

        @Test
        @DisplayName("cancelOrder: a malformed id is not found rather than a database error")
        public void cancelOrderMalformedId() {
            assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder("1; drop table", null));
            verify(orderMapper, never()).findById(any());
        }

        @Test
        @DisplayName("cancelOrder: Order not found throws OrderNotFoundException")
        public void cancelOrderNotFound() {
            when(orderMapper.findById("99")).thenReturn(null);
            assertThrows(OrderNotFoundException.class, () -> orderService.cancelOrder("99", null));
        }

        @Test
        @DisplayName("cancelOrder: Order not in NEW state throws DuplicateOrderException")
        public void cancelOrderNotNew() {
            OrderRecord filled = new OrderRecord();
            filled.setStatus(OrderStatus.FILLED);
            when(orderMapper.findById("1")).thenReturn(filled);

            assertThrows(DuplicateOrderException.class, () -> orderService.cancelOrder("1", null));
        }

        @Test
        @DisplayName("cancelOrder: DB update 0 rows throws DuplicateOrderException")
        public void cancelOrderDbUpdateZero() {
            OrderRecord existing = new OrderRecord();
            existing.setStatus(OrderStatus.NEW);
            when(orderMapper.findById("1")).thenReturn(existing);
            when(orderMapper.cancelOrder("1")).thenReturn(0);

            assertThrows(DuplicateOrderException.class, () -> orderService.cancelOrder("1", null));
        }
    }

    @Nested
    @DisplayName("History")
    class History {

        @Test
        @DisplayName("getOrders: Returns list of orders for account")
        public void getOrdersSuccess() {
            OrderHistoryEntryDto entry = new OrderHistoryEntryDto(
                    "1", 101L, "AAPL", OrderSide.BUY, 10L, new BigDecimal("150.00"),
                    null, null, OrderStatus.NEW, null, null, Instant.now()
            );
            when(orderMapper.findByAccountAndFilters(101L, "NEW", null, null)).thenReturn(List.of(entry));

            List<OrderHistoryEntryDto> result = orderService.getOrders(101L, "NEW", null, null);
            assertEquals(1, result.size());
            assertEquals("AAPL", result.get(0).symbol());
        }
    }
}

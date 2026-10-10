package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.enums.OrderSide;
import com.trading.tradeapi.enums.OrderType;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.OrderValidationException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
public class OrderValidatorUnitTest {
    private static final String KEY = "idempotency-key-123";
    private static final String SYMBOL = "AAPL";

    @Mock
    private OrderMapper orderMapper;
    @Mock
    private AccountMapper accountMapper;
    @Mock
    private InstrumentDiscoveryService instrumentDiscoveryService;

    @InjectMocks
    private OrderValidator orderValidator;

    @BeforeEach
    public void allowIdempotencyKey() {
        when(orderMapper.findByIdempotencyKey(KEY)).thenReturn(null);
    }

    private PlaceOrderRequestDto buy(long quantity, String price) {
        return new PlaceOrderRequestDto(101L, SYMBOL, OrderSide.BUY, quantity,
                new BigDecimal(price), OrderType.LIMIT, KEY);
    }

    private PlaceOrderRequestDto sell(long quantity, String price) {
        return new PlaceOrderRequestDto(101L, SYMBOL, OrderSide.SELL, quantity,
                new BigDecimal(price), OrderType.LIMIT, KEY);
    }

    private AccountRecord account(AccountStatus status, String balance) {
        return new AccountRecord(101L, 1L, "Holder", "USD",
                new BigDecimal(balance), status, 1L, null);
    }

    private InstrumentRecord instrument(String status) {
        return new InstrumentRecord(1L, SYMBOL, SYMBOL, "EQUITY", "USD", status, null);
    }

    @Nested
    @DisplayName("Idempotency")
    class Idempotency {

        @Test
        @DisplayName("validate: duplicated idempotency key is rejected with ORD-409")
        public void duplicateIdempotencyKeyRejected() {
            when(orderMapper.findByIdempotencyKey(KEY)).thenReturn(new com.trading.tradeapi.entity.OrderRecord());

            assertThrows(DuplicateOrderException.class, () -> orderValidator.validate(buy(1L, "10.00")));
        }
    }

    @Nested
    @DisplayName("Account and instrument")
    class AccountAndInstrument {

        @Test
        @DisplayName("validate: unknown account is rejected with ACC-404")
        public void unknownAccountRejected() {
            when(accountMapper.findById(101L)).thenReturn(null);

            assertThrows(AccountNotFoundException.class, () -> orderValidator.validate(buy(1L, "10.00")));
        }

        @Test
        @DisplayName("validate: non-active account is rejected with ACC-403")
        public void inactiveAccountRejected() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.SUSPENDED, "1000.00"));

            assertThrows(AccountNotActiveException.class, () -> orderValidator.validate(buy(1L, "10.00")));
        }

        @Test
        @DisplayName("validate: unknown instrument is rejected with INS-404")
        public void unknownInstrumentRejected() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "1000.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL))
                    .thenThrow(new InstrumentNotFoundException());

            assertThrows(InstrumentNotFoundException.class, () -> orderValidator.validate(buy(1L, "10.00")));
        }

        @Test
        @DisplayName("validate: delisted instrument is rejected with INS-404")
        public void delistedInstrumentRejected() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "1000.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL))
                    .thenThrow(new InstrumentNotFoundException());

            assertThrows(InstrumentNotFoundException.class, () -> orderValidator.validate(buy(1L, "10.00")));
        }

        @Test
        @DisplayName("validate: an inactive account is refused before the instrument is looked up")
        public void inactiveAccountRefusedBeforeInstrumentLookup() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.INACTIVE, "1000.00"));

            assertThrows(AccountNotActiveException.class, () -> orderValidator.validate(sell(1L, "10.00")));
            org.mockito.Mockito.verifyNoInteractions(instrumentDiscoveryService);
        }
    }

    @Nested
    @DisplayName("Funds and holdings are the executor's decision")
    class FundsAndHoldingsAreTheExecutorSDecision {

        @Test
        @DisplayName("validate: BUY costing more than the cash balance is admitted; the executor rejects it")
        public void buyBeyondCashIsAdmitted() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "100.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL)).thenReturn(instrument("ACTIVE"));

            assertDoesNotThrow(() -> orderValidator.validate(buy(10L, "150.00")));
        }

        @Test
        @DisplayName("validate: BUY with sufficient cash passes")
        public void buySufficientFundsPasses() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "10000.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL)).thenReturn(instrument("ACTIVE"));

            assertDoesNotThrow(() -> orderValidator.validate(buy(10L, "150.00")));
        }

        @Test
        @DisplayName("validate: SELL with no holding is admitted; the executor rejects it")
        public void sellWithoutHoldingIsAdmitted() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "1000.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL)).thenReturn(instrument("ACTIVE"));

            assertDoesNotThrow(() -> orderValidator.validate(sell(10L, "150.00")));
        }

        @Test
        @DisplayName("validate: a zero-balance account can still submit; admission never reads the balance")
        public void zeroBalanceStillAdmitted() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "0.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL)).thenReturn(instrument("ACTIVE"));

            assertDoesNotThrow(() -> orderValidator.validate(buy(1L, "1.00")));
        }

        @Test
        @DisplayName("validate: a MARKET order priced beyond the balance is admitted; the executor rejects it")
        public void marketOrderBeyondBalanceIsAdmitted() {
            when(accountMapper.findById(101L)).thenReturn(account(AccountStatus.ACTIVE, "500.00"));
            when(instrumentDiscoveryService.resolveTradeableInstrument(SYMBOL)).thenReturn(instrument("ACTIVE"));

            PlaceOrderRequestDto request = new PlaceOrderRequestDto(
                    101L, SYMBOL, OrderSide.BUY, 4L, new BigDecimal("150.00"), OrderType.MARKET, KEY);

            assertDoesNotThrow(() -> orderValidator.validate(request));
        }
    }

    @Nested
    @DisplayName("Order type and price")
    class OrderTypeAndPrice {

        @Test
        @DisplayName("PlaceOrderRequestDto: missing order type defaults to LIMIT")
        public void missingOrderTypeDefaultsToLimit() {
            PlaceOrderRequestDto request = new PlaceOrderRequestDto(
                    101L, SYMBOL, OrderSide.BUY, 1L, new BigDecimal("10.00"), null, KEY);

            assertEquals(OrderType.LIMIT, request.orderType());
        }

        @Test
        @DisplayName("validate: a LIMIT order without a price is rejected with VAL-422")
        public void limitOrderWithoutPriceRejected() {
            PlaceOrderRequestDto request = new PlaceOrderRequestDto(
                    101L, SYMBOL, OrderSide.BUY, 1L, null, OrderType.LIMIT, KEY);

            OrderValidationException thrown = assertThrows(
                    OrderValidationException.class, () -> orderValidator.validate(request));

            assertEquals("ORD-422", thrown.getCode());
        }

        @Test
        @DisplayName("validate: a MARKET order the pricer could not price is rejected with VAL-422")
        public void marketOrderWithoutResolvedPriceRejected() {
            PlaceOrderRequestDto request = new PlaceOrderRequestDto(
                    101L, SYMBOL, OrderSide.BUY, 1L, null, OrderType.MARKET, KEY);

            assertThrows(OrderValidationException.class, () -> orderValidator.validate(request));
        }

        @Test
        @DisplayName("validate: a non-positive price is rejected before the balance is touched")
        public void nonPositivePriceRejected() {
            PlaceOrderRequestDto request = new PlaceOrderRequestDto(
                    101L, SYMBOL, OrderSide.BUY, 1L, new BigDecimal("0.00"), OrderType.LIMIT, KEY);

            assertThrows(OrderValidationException.class, () -> orderValidator.validate(request));
        }
    }
}

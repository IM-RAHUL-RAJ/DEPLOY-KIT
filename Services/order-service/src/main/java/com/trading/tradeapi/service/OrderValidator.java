package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.entity.OrderRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.OrderValidationException;
import com.trading.tradeapi.mapper.AccountMapper;
import com.trading.tradeapi.mapper.OrderMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single authority for order admission. Runs before an order is persisted or
 * published so no malformed order, no order for an unusable account and no order
 * for an untradeable instrument ever reaches the executor.
 *
 * <p>Cash and holdings are deliberately NOT checked here. Whether an order can be
 * paid for depends on the price it fills at, which only the executor knows, so the
 * executor alone decides: the order is accepted as NEW and moves to REJECTED with
 * {@code INSUFFICIENT_FUNDS} or {@code INSUFFICIENT_HOLDINGS} as its recorded
 * reason. That keeps every attempt in the order history, where the blotter shows it.
 */
@Component
public class OrderValidator {

    private static final Logger logger = LoggerFactory.getLogger(OrderValidator.class);

    private final OrderMapper orderMapper;
    private final AccountMapper accountMapper;
    private final InstrumentDiscoveryService instrumentDiscoveryService;

    public OrderValidator(OrderMapper orderMapper,
                          AccountMapper accountMapper,
                          InstrumentDiscoveryService instrumentDiscoveryService) {
        this.orderMapper = orderMapper;
        this.accountMapper = accountMapper;
        this.instrumentDiscoveryService = instrumentDiscoveryService;
    }

    /** Admission checks, cheapest first. Funds and holdings are the executor's. */
    public void validate(PlaceOrderRequestDto request) {
        requireUnusedIdempotencyKey(request.idempotencyKey());
        requireResolvablePrice(request);
        requireActiveAccount(request.accountId());
        requireTradeableInstrument(request.ticker());
    }

    /**
     * A limit order is only executable with a client price; a market order is
     * priced from the live book before it reaches this point, so a missing price
     * here means the caller could not be priced and the order is refused.
     */
    private void requireResolvablePrice(PlaceOrderRequestDto request) {
        if (request.price() == null || request.price().signum() <= 0) {
            throw new OrderValidationException(
                    "ORD-422", "A positive price is required: limit orders must state one and market orders must be priceable");
        }
    }

    private void requireUnusedIdempotencyKey(String idempotencyKey) {
        OrderRecord existing = orderMapper.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            logger.warn("Duplicate order attempt for idempotency key");
            throw new DuplicateOrderException("ORD-409", "Order with this idempotency key already exists");
        }
    }

    private void requireActiveAccount(Long accountId) {
        AccountRecord account = accountMapper.findById(accountId);
        if (account == null) {
            throw new AccountNotFoundException();
        }
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
    }

    private void requireTradeableInstrument(String symbol) {
        instrumentDiscoveryService.resolveTradeableInstrument(symbol);
    }
}

package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.AccountResponseDto;
import com.trading.tradeapi.dto.BalanceResponseDto;
import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.enums.AccountStatus;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.mapper.AccountMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AccountService {

    private final AccountMapper accountMapper;
    private final HoldingService holdingService;
    private final OrderService orderService;

    public AccountService(AccountMapper accountMapper,
                          HoldingService holdingService,
                          OrderService orderService) {
        this.accountMapper = accountMapper;
        this.holdingService = holdingService;
        this.orderService = orderService;
    }

    private AccountRecord validateAccountExists(Long id) {
        AccountRecord acc = accountMapper.findById(id);
        if (acc == null) {
            throw new AccountNotFoundException();
        }
        return acc;
    }

    public AccountRecord getAccountRecord(Long id) {
        return validateAccountExists(id);
    }

    public void validateAccountActive(AccountRecord account) {
        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new AccountNotActiveException();
        }
    }

    @Transactional(readOnly = true)
    public AccountResponseDto getAccount(Long id) {
        AccountRecord acc = validateAccountExists(id);
        return new AccountResponseDto(
                acc.getAccountId(),
                formatAccountId(acc.getAccountId()),
                acc.getHolderName(),
                acc.getCashBalance(),
                acc.getCurrency(),
                acc.getStatus(),
                acc.getVersion(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public BalanceResponseDto getBalance(Long id) {
        AccountRecord acc = validateAccountExists(id);
        return new BalanceResponseDto(
                acc.getAccountId(),
                acc.getCashBalance(),
                acc.getCurrency(),
                Instant.now()
        );
    }

    @Transactional(readOnly = true)
    public List<PositionResponseDto> getHoldings(Long id) {
        validateAccountExists(id);
        return holdingService.getHoldingsByAccountId(id);
    }

    @Transactional(readOnly = true)
    public List<OrderHistoryEntryDto> getOrders(Long id, String status, Instant from, Instant to) {
        validateAccountExists(id);
        return orderService.getOrders(id, status, from, to);
    }

    private String formatAccountId(Long accountId) {
        return "ACC-" + String.format("%06d", accountId);
    }
}

package com.trading.tradeapi.strategy.controller;

import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.strategy.dto.CreateStrategyRequest;
import com.trading.tradeapi.strategy.dto.StrategyDto;
import com.trading.tradeapi.strategy.dto.StrategyExecutionDto;
import com.trading.tradeapi.strategy.dto.StrategyPreview;
import com.trading.tradeapi.strategy.dto.UpdateStrategyStatusRequest;
import com.trading.tradeapi.strategy.service.StrategyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Automated strategy routes. On every route the path account must be the token's
 * account; a mismatch is 403 ACC-403, logged. A strategy's own order token (subject
 * {@code strategy:<id>}) cannot manage strategies: it exists only to place one order.
 */
@RestController
@RequestMapping("/api/v1/strategies")
public class StrategyController {

    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);

    private final StrategyService strategies;

    public StrategyController(StrategyService strategies) {
        this.strategies = strategies;
    }

    @GetMapping("/{accountId}")
    public List<StrategyDto> list(@PathVariable("accountId") long accountId, HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return strategies.list(accountId);
    }

    @PostMapping("/{accountId}")
    public ResponseEntity<StrategyDto> create(@PathVariable("accountId") long accountId,
                                              @Valid @RequestBody CreateStrategyRequest body,
                                              HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(strategies.create(accountId, body));
    }

    @PatchMapping("/{accountId}/{strategyId}")
    public StrategyDto setStatus(@PathVariable("accountId") long accountId,
                                 @PathVariable("strategyId") long strategyId,
                                 @Valid @RequestBody UpdateStrategyStatusRequest body,
                                 HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return strategies.setStatus(accountId, strategyId, body.status());
    }

    @DeleteMapping("/{accountId}/{strategyId}")
    public ResponseEntity<Void> delete(@PathVariable("accountId") long accountId,
                                       @PathVariable("strategyId") long strategyId,
                                       HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        strategies.delete(accountId, strategyId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{accountId}/{strategyId}/executions")
    public List<StrategyExecutionDto> executions(@PathVariable("accountId") long accountId,
                                                 @PathVariable("strategyId") long strategyId,
                                                 HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return strategies.executions(accountId, strategyId);
    }

    @GetMapping("/{accountId}/{strategyId}/preview")
    public StrategyPreview preview(@PathVariable("accountId") long accountId,
                                   @PathVariable("strategyId") long strategyId,
                                   HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return strategies.preview(accountId, strategyId);
    }

    private void requireOwnedAccount(long accountId, HttpServletRequest request) {
        Long tokenAccount = (Long) request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);
        Object subject = request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR);
        boolean strategyToken = subject instanceof String s && s.startsWith("strategy:");
        if (tokenAccount == null || tokenAccount != accountId || strategyToken) {
            log.warn("Strategies for account {} refused to a token for account {}", accountId, tokenAccount);
            throw new UnauthorizedAccountAccessException();
        }
    }
}

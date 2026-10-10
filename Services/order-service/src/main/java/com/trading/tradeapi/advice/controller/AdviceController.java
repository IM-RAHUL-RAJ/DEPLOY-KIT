package com.trading.tradeapi.advice.controller;

import com.trading.tradeapi.advice.dto.AdviceResponse;
import com.trading.tradeapi.advice.dto.Methodology;
import com.trading.tradeapi.advice.dto.TradeSignal;
import com.trading.tradeapi.advice.service.AdviceService;
import com.trading.tradeapi.advice.service.SignalEngine;
import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trade advice routes. The token was verified before these run; whether this caller
 * may see this account's advice is decided here, on every route: the path account
 * must be the token's account, and a mismatch is 403 ACC-403, logged.
 */
@RestController
@RequestMapping("/api/v1/advice")
public class AdviceController {

    private static final Logger log = LoggerFactory.getLogger(AdviceController.class);

    private final AdviceService advice;

    public AdviceController(AdviceService advice) {
        this.advice = advice;
    }

    /** The formulas behind every signal. Not account data, so any signed-in caller may read it. */
    @GetMapping("/methodology")
    public ResponseEntity<Methodology> methodology() {
        return ResponseEntity.ok(SignalEngine.methodology(AdviceService.DISCLAIMER));
    }

    @GetMapping("/{accountId:\\d+}")
    public ResponseEntity<AdviceResponse> forAccount(@PathVariable("accountId") long accountId,
                                                     HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        String uuid = (String) request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_UUID_ATTR);
        return ResponseEntity.ok(advice.forAccount(accountId, uuid));
    }

    @GetMapping("/{accountId:\\d+}/{symbol}")
    public ResponseEntity<TradeSignal> forSymbol(@PathVariable("accountId") long accountId,
                                                 @PathVariable("symbol") String symbol,
                                                 HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        if (symbol == null || !symbol.matches("(?i)^[A-Z0-9.:\\-]{1,20}$")) {
            throw new IllegalArgumentException("symbol must be 1 to 20 characters of A-Z, 0-9, '.', ':' or '-'");
        }
        return ResponseEntity.ok(advice.forSymbol(accountId, symbol));
    }

    private void requireOwnedAccount(long accountId, HttpServletRequest request) {
        Long tokenAccount = (Long) request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);
        if (tokenAccount == null || tokenAccount != accountId) {
            log.warn("Advice for account {} refused to a token for account {}", accountId, tokenAccount);
            throw new UnauthorizedAccountAccessException();
        }
    }
}

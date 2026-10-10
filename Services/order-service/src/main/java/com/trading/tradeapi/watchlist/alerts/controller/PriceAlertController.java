package com.trading.tradeapi.watchlist.alerts.controller;

import com.trading.tradeapi.watchlist.alerts.dto.CreatePriceAlertRequest;
import com.trading.tradeapi.watchlist.alerts.dto.PriceAlertDto;
import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The alert routes, mounted under the watchlist path because the module is "watchlists and price
 * alerts". Account ownership comes from the verified token claim, exactly as on the watchlist
 * routes: there is no path parameter a caller could point at another account.
 */
@RestController
@RequestMapping("/api/v1/watchlist/alerts")
public class PriceAlertController {

    private static final Logger log = LoggerFactory.getLogger(PriceAlertController.class);

    private final AlertService alertService;

    public PriceAlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    /** GET /api/v1/watchlist/alerts — the account's alerts, newest first. */
    @GetMapping
    public ResponseEntity<List<PriceAlertDto>> list() {
        long accountId = requireAccount();
        return ResponseEntity.ok(alertService.list(accountId));
    }

    /** POST /api/v1/watchlist/alerts — arm a one-shot alert. 422 on an unknown symbol or the cap. */
    @PostMapping
    public ResponseEntity<PriceAlertDto> create(@Valid @RequestBody CreatePriceAlertRequest request) {
        long accountId = requireAccount();
        return ResponseEntity.ok(alertService.create(accountId, request));
    }

    /** DELETE /api/v1/watchlist/alerts/{alertId} — disarm; idempotent. ACC-404 to any other caller. */
    @DeleteMapping("/{alertId}")
    public ResponseEntity<PriceAlertDto> cancel(@PathVariable Long alertId) {
        long accountId = requireAccount();
        return ResponseEntity.ok(alertService.cancel(accountId, alertId));
    }

    /**
     * The verified account claim, or the same {@code ACC-403} an administrator gets everywhere
     * else: the session is valid, it just does not own an account.
     */
    private long requireAccount() {
        Long accountId = JwtAuthenticationFilter.getAccountIdFromContext();
        if (accountId == null) {
            log.warn("Request without an account claim on the alerts routes");
            throw new DomainException("ACC-403", "Account not accessible", HttpStatus.FORBIDDEN);
        }
        return accountId;
    }
}
package com.trading.tradeapi.portfolio.controller;

import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.portfolio.dto.PnlResponseDto;
import com.trading.tradeapi.portfolio.dto.PortfolioSummaryDto;
import com.trading.tradeapi.portfolio.dto.PricedPositionDto;
import com.trading.tradeapi.portfolio.service.PortfolioService;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Portfolio routes, one {@code accountId} in every path and every path checked against the
 * verified token, exactly as the preferences and notifications modules do. The service then
 * prices the holdings in one batched quote call and runs the realised P&amp;L walk.
 */
@RestController
@RequestMapping("/api/v1/portfolio")
public class PortfolioController {

    private static final Logger log = LoggerFactory.getLogger(PortfolioController.class);

    private final PortfolioService portfolioService;

    public PortfolioController(PortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    @GetMapping("/{accountId}")
    public ResponseEntity<PortfolioSummaryDto> summary(@PathVariable("accountId") Long accountId,
                                                       HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return ResponseEntity.ok(portfolioService.summary(accountId));
    }

    @GetMapping("/{accountId}/positions")
    public ResponseEntity<List<PricedPositionDto>> positions(
            @PathVariable("accountId") Long accountId,
            @RequestParam(value = "symbol", required = false) String symbol,
            HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return ResponseEntity.ok(portfolioService.positions(accountId, symbol));
    }

    @GetMapping("/{accountId}/pnl")
    public ResponseEntity<PnlResponseDto> pnl(
            @PathVariable("accountId") Long accountId,
            @RequestParam(value = "symbol", required = false) String symbol,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return ResponseEntity.ok(portfolioService.pnl(accountId, from, to));
    }

    /**
     * The path account must be the account in the verified token. A mismatch is 403, not 404,
     * so a probe cannot learn about an account it was never allowed to see.
     */
    private void requireOwnedAccount(Long accountId, HttpServletRequest request) {
        Long authenticatedAccountId = (Long) request
                .getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);

        if (authenticatedAccountId == null) {
            log.warn("Portfolio route reached without an account claim: accountId={}", accountId);
            throw new UnauthorizedAccountAccessException();
        }
        if (!authenticatedAccountId.equals(accountId)) {
            log.warn("Cross-account portfolio access refused: claim={} path={}",
                    authenticatedAccountId, accountId);
            throw new UnauthorizedAccountAccessException();
        }
    }
}
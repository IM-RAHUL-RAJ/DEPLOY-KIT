package com.trading.tradeapi.watchlist.controller;

import com.trading.tradeapi.watchlist.dto.AddWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.CreateWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.MoveWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.RenameWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.watchlist.service.WatchlistService;
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
 * The named, multiple watchlists a customer keeps, mounted under the plural path. Each route
 * takes the account from the verified token claim, exactly as the legacy singular routes and the
 * alerts routes do, so a caller can never address another account's list. Errors carry the
 * platform envelope through {@link com.trading.tradeapi.exception.GlobalExceptionHandler}.
 */
@RestController
@RequestMapping("/api/v1/watchlists")
public class WatchlistsController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistsController.class);

    private final WatchlistService watchlistService;

    public WatchlistsController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /** GET /api/v1/watchlists — the account's watchlists, oldest first, instruments and prices. */
    @GetMapping
    public ResponseEntity<List<WatchlistDto>> list() {
        long accountId = requireAccount();
        return ResponseEntity.ok(watchlistService.listWatchlists(accountId, requireUuid()));
    }

    /** POST /api/v1/watchlists — create an empty named watchlist. 422 on a bad or taken name. */
    @PostMapping
    public ResponseEntity<WatchlistDto> create(@RequestBody CreateWatchlistRequest request) {
        long accountId = requireAccount();
        return ResponseEntity.ok(watchlistService.createWatchlist(accountId, requireUuid(), request));
    }

    /** PATCH /api/v1/watchlists/{watchlistId} — rename one of the account's own lists. */
    @PatchMapping("/{watchlistId}")
    public ResponseEntity<WatchlistDto> rename(@PathVariable Long watchlistId,
                                               @RequestBody RenameWatchlistRequest request) {
        long accountId = requireAccount();
        return ResponseEntity.ok(watchlistService.renameWatchlist(accountId, requireUuid(), watchlistId, request));
    }

    /** DELETE /api/v1/watchlists/{watchlistId} — delete a list; never the account's last. */
    @DeleteMapping("/{watchlistId}")
    public ResponseEntity<Void> delete(@PathVariable Long watchlistId) {
        long accountId = requireAccount();
        watchlistService.deleteWatchlist(accountId, requireUuid(), watchlistId);
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    /** POST /api/v1/watchlists/{watchlistId}/instruments — add a symbol to that list. */
    @PostMapping("/{watchlistId}/instruments")
    public ResponseEntity<WatchlistDto> addInstrument(@PathVariable Long watchlistId,
                                                      @RequestBody AddWatchlistInstrumentRequest request) {
        long accountId = requireAccount();
        return ResponseEntity.ok(watchlistService.addInstrumentToList(accountId, requireUuid(), watchlistId, request));
    }

    /** DELETE /api/v1/watchlists/{watchlistId}/instruments/{watchlistInstrumentId} — remove one entry. */
    @DeleteMapping("/{watchlistId}/instruments/{watchlistInstrumentId}")
    public ResponseEntity<WatchlistDto> removeInstrument(@PathVariable Long watchlistId,
                                                         @PathVariable Long watchlistInstrumentId) {
        long accountId = requireAccount();
        return ResponseEntity.ok(watchlistService.removeInstrumentFromList(
                accountId, requireUuid(), watchlistId, watchlistInstrumentId));
    }

    /** POST /api/v1/watchlists/instruments/{watchlistInstrumentId}/move — between the account's own lists. */
    @PostMapping("/instruments/{watchlistInstrumentId}/move")
    public ResponseEntity<WatchlistDto> move(@PathVariable Long watchlistInstrumentId,
                                             @RequestBody MoveWatchlistInstrumentRequest request) {
        long accountId = requireAccount();
        Long target = request == null ? null : request.targetWatchlistId();
        return ResponseEntity.ok(watchlistService.moveInstrument(accountId, requireUuid(), watchlistInstrumentId, target));
    }

    private long requireAccount() {
        Long accountId = JwtAuthenticationFilter.getAccountIdFromContext();
        if (accountId == null) {
            log.warn("Request without an account claim on the watchlists routes");
            throw new DomainException("ACC-403", "Account not accessible", HttpStatus.FORBIDDEN);
        }
        return accountId;
    }

    private String requireUuid() {
        String userUuid = JwtAuthenticationFilter.getUserUuidFromContext();
        if (userUuid == null) {
            throw new DomainException("ACC-403", "Account not accessible", HttpStatus.FORBIDDEN);
        }
        return userUuid;
    }
}
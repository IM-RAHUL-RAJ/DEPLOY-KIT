package com.trading.tradeapi.watchlist.controller;

import com.trading.tradeapi.watchlist.dto.AddWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.watchlist.service.WatchlistService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/watchlist")
public class WatchlistController {

    private static final Logger log = LoggerFactory.getLogger(WatchlistController.class);

    private final WatchlistService watchlistService;

    public WatchlistController(WatchlistService watchlistService) {
        this.watchlistService = watchlistService;
    }

    /**
     * GET /api/v1/watchlist
     * Retrieves the watchlist for the authenticated user's account.
     */
    @GetMapping
    public ResponseEntity<WatchlistDto> getWatchlist() {
        Long accountId = JwtAuthenticationFilter.getAccountIdFromContext();
        String userUuid = JwtAuthenticationFilter.getUserUuidFromContext();
        
        log.info("Watchlist request - accountId: {}, userUuid: {}", accountId, userUuid);
        
        if (accountId == null || userUuid == null) {
            log.warn("Missing context - accountId: {}, userUuid: {}", accountId, userUuid);
            // No account claim: an administrator, who has no watchlist. 403, not 401, because the
            // session is valid; a 401 would make the client treat it as expired and sign out.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            WatchlistDto watchlist = watchlistService.getWatchlist(accountId, userUuid);
            log.info("Successfully retrieved watchlist for account {}", accountId);
            return ResponseEntity.ok(watchlist);
        } catch (IllegalArgumentException e) {
            log.warn("Access denied to watchlist: {}", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        } catch (Exception e) {
            log.error("Error retrieving watchlist for account {}: {}", accountId, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * POST /api/v1/watchlist/instruments
     * Adds an instrument to the watchlist.
     */
    @PostMapping("/instruments")
    public ResponseEntity<WatchlistDto> addInstrument(@RequestBody AddWatchlistInstrumentRequest request) {
        Long accountId = JwtAuthenticationFilter.getAccountIdFromContext();
        String userUuid = JwtAuthenticationFilter.getUserUuidFromContext();
        
        if (accountId == null || userUuid == null) {
            // No account claim: an administrator, who has no watchlist. 403, not 401, because the
            // session is valid; a 401 would make the client treat it as expired and sign out.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            WatchlistDto watchlist = watchlistService.addInstrument(accountId, userUuid, request);
            return ResponseEntity.ok(watchlist);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request to add instrument for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error adding instrument to watchlist for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * DELETE /api/v1/watchlist/instruments/{id}
     * Removes an instrument from the watchlist.
     */
    @DeleteMapping("/instruments/{id}")
    public ResponseEntity<WatchlistDto> removeInstrument(@PathVariable Long id) {
        Long accountId = JwtAuthenticationFilter.getAccountIdFromContext();
        String userUuid = JwtAuthenticationFilter.getUserUuidFromContext();
        
        if (accountId == null || userUuid == null) {
            // No account claim: an administrator, who has no watchlist. 403, not 401, because the
            // session is valid; a 401 would make the client treat it as expired and sign out.
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        try {
            WatchlistDto watchlist = watchlistService.removeInstrument(accountId, userUuid, id);
            return ResponseEntity.ok(watchlist);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request to remove instrument for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Error removing instrument from watchlist for account {}: {}", accountId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}

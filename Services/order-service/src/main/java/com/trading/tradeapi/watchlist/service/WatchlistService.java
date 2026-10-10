package com.trading.tradeapi.watchlist.service;

import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import com.trading.tradeapi.watchlist.dto.AddWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.CreateWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.RenameWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.watchlist.dto.WatchlistInstrumentDto;
import com.trading.tradeapi.entity.InstrumentQuoteRecord;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.watchlist.entity.WatchlistInstrumentRecord;
import com.trading.tradeapi.watchlist.entity.WatchlistRecord;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.mapper.UserMapper;
import com.trading.tradeapi.watchlist.mapper.WatchlistMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class WatchlistService {

    private static final Logger log = LoggerFactory.getLogger(WatchlistService.class);
    private static final int MAX_WATCHLIST_INSTRUMENTS = 25;
    private static final int MAX_WATCHLIST_NAME_LENGTH = 40;

    private final WatchlistMapper watchlistMapper;
    private final InstrumentMapper instrumentMapper;
    private final UserMapper userMapper;
    private final MarketDataService marketData;
    private final AlertService alertService;

    public WatchlistService(WatchlistMapper watchlistMapper,
                           InstrumentMapper instrumentMapper,
                           UserMapper userMapper,
                           MarketDataService marketData,
                           AlertService alertService) {
        this.watchlistMapper = watchlistMapper;
        this.instrumentMapper = instrumentMapper;
        this.userMapper = userMapper;
        this.marketData = marketData;
        this.alertService = alertService;
    }

    /**
     * Gets or creates the primary watchlist for an account — the oldest list. Only
     * client accounts can have watchlists. This is the legacy single-list route;
     * new clients read {@link #listWatchlists}.
     */
    public WatchlistDto getWatchlist(Long accountId, String userUuid) {
        log.info("getWatchlist called - accountId: {}, userUuid: {}", accountId, userUuid);

        try {
            verifyIsClient(userUuid);
        } catch (Exception e) {
            log.error("Client verification failed for userUuid: {}", userUuid, e);
            throw e;
        }

        WatchlistRecord watchlist = watchlistMapper.findByAccountId(accountId);
        log.debug("Found watchlist for account {}: {}", accountId, watchlist != null);

        if (watchlist == null) {
            log.info("Creating new watchlist for account {}", accountId);
            watchlistMapper.createWatchlist(accountId);
            watchlist = watchlistMapper.findByAccountId(accountId);
            log.info("Created watchlist with id: {}", watchlist != null ? watchlist.getWatchlistId() : "null");
        }

        return buildWatchlistDto(watchlist);
    }

    /**
     * Every named watchlist the account owns, oldest first. Does not create anything.
     */
    public List<WatchlistDto> listWatchlists(Long accountId, String userUuid) {
        verifyClientAccess(userUuid);
        List<WatchlistRecord> records = watchlistMapper.findAllByAccountId(accountId);
        List<WatchlistDto> lists = new ArrayList<>();
        for (WatchlistRecord record : records) {
            lists.add(buildWatchlistDto(record));
        }
        log.info("Listed {} watchlists for account {}", lists.size(), accountId);
        return lists;
    }

    /**
     * Creates an empty named watchlist. The name is trimmed and unique per account.
     */
    public WatchlistDto createWatchlist(Long accountId, String userUuid, CreateWatchlistRequest request) {
        verifyClientAccess(userUuid);
        String name = normalizeName(request == null ? null : request.name());
        ensureNameAvailable(accountId, name, null);
        watchlistMapper.createNamedWatchlist(accountId, name);
        WatchlistRecord created = watchlistMapper.findByAccountIdAndName(accountId, name);
        log.info("Created watchlist '{}' for account {}", name, accountId);
        return buildWatchlistDto(created);
    }

    /**
     * Renames one of the account's own watchlists, under the same name rules as create.
     */
    public WatchlistDto renameWatchlist(Long accountId, String userUuid, Long watchlistId,
                                        RenameWatchlistRequest request) {
        verifyClientAccess(userUuid);
        String name = normalizeName(request == null ? null : request.name());
        WatchlistRecord owned = ownedWatchlist(accountId, watchlistId);
        ensureNameAvailable(accountId, name, owned.getWatchlistId());
        watchlistMapper.renameWatchlist(owned.getWatchlistId(), name);
        log.info("Renamed watchlist {} to '{}' for account {}", owned.getWatchlistId(), name, accountId);
        return buildWatchlistDto(ownedWatchlist(accountId, watchlistId));
    }

    /**
     * Deletes one of the account's own watchlists. A customer must keep at least one.
     */
    public void deleteWatchlist(Long accountId, String userUuid, Long watchlistId) {
        verifyClientAccess(userUuid);
        WatchlistRecord owned = ownedWatchlist(accountId, watchlistId);
        if (watchlistMapper.countByAccountId(accountId) <= 1) {
            throw invalid("A customer must keep at least one watchlist");
        }
        watchlistMapper.deleteWatchlist(owned.getWatchlistId());
        log.info("Deleted watchlist {} for account {}", owned.getWatchlistId(), accountId);
    }

    /**
     * Adds an instrument to a specific named watchlist.
     */
    public WatchlistDto addInstrumentToList(Long accountId, String userUuid, Long watchlistId,
                                            AddWatchlistInstrumentRequest request) {
        verifyClientAccess(userUuid);
        WatchlistRecord target = ownedWatchlist(accountId, watchlistId);
        addInstrumentInternal(accountId, target, request);
        return buildWatchlistDto(ownedWatchlist(accountId, watchlistId));
    }

    /**
     * Removes one entry from a specific named watchlist.
     */
    public WatchlistDto removeInstrumentFromList(Long accountId, String userUuid, Long watchlistId,
                                                 Long watchlistInstrumentId) {
        verifyClientAccess(userUuid);
        WatchlistRecord owned = ownedWatchlist(accountId, watchlistId);
        WatchlistInstrumentRecord entry =
                watchlistMapper.findInstrumentByIdAndAccount(watchlistInstrumentId, accountId);
        if (entry == null || !entry.getWatchlistId().equals(watchlistId)) {
            throw notFound("Watchlist entry not found");
        }
        watchlistMapper.removeInstrument(watchlistInstrumentId);
        watchlistMapper.updateWatchlistTimestamp(owned.getWatchlistId());
        log.info("Removed entry {} from watchlist {} for account {}", watchlistInstrumentId,
                owned.getWatchlistId(), accountId);
        return buildWatchlistDto(owned);
    }

    /**
     * Moves a watchlist entry between two of the account's own watchlists.
     */
    public WatchlistDto moveInstrument(Long accountId, String userUuid, Long watchlistInstrumentId,
                                       Long targetWatchlistId) {
        verifyClientAccess(userUuid);
        if (targetWatchlistId == null) {
            throw invalid("A target watchlist is required");
        }
        WatchlistInstrumentRecord entry =
                watchlistMapper.findInstrumentByIdAndAccount(watchlistInstrumentId, accountId);
        if (entry == null) {
            throw notFound("Watchlist entry not found");
        }
        if (entry.getWatchlistId().equals(targetWatchlistId)) {
            return buildWatchlistDto(ownedWatchlist(accountId, targetWatchlistId));
        }
        WatchlistRecord target = ownedWatchlist(accountId, targetWatchlistId);
        if (watchlistMapper.hasInstrument(target.getWatchlistId(), entry.getInstrumentId())) {
            throw invalid("Instrument is already on target watchlist");
        }
        if (watchlistMapper.countInstruments(target.getWatchlistId()) >= MAX_WATCHLIST_INSTRUMENTS) {
            throw invalid("Target watchlist is at maximum capacity (" + MAX_WATCHLIST_INSTRUMENTS + " instruments)");
        }
        watchlistMapper.removeInstrument(watchlistInstrumentId);
        watchlistMapper.updateWatchlistTimestamp(entry.getWatchlistId());
        watchlistMapper.addInstrument(target.getWatchlistId(), entry.getInstrumentId());
        watchlistMapper.updateWatchlistTimestamp(target.getWatchlistId());
        log.info("Moved watchlist entry {} to list {} for account {}", watchlistInstrumentId,
                target.getWatchlistId(), accountId);
        return buildWatchlistDto(target);
    }

    /**
     * Adds an instrument to the primary watchlist (legacy route). Same rules as
     * {@link #addInstrumentToList}, but failures surface as {@link IllegalArgumentException}
     * for the original single-list controller.
     */
    public WatchlistDto addInstrument(Long accountId, String userUuid, AddWatchlistInstrumentRequest request) {
        log.info("addInstrument called - accountId: {}, symbol: {}",
                 accountId, request != null ? request.symbol() : "null");

        verifyIsClient(userUuid);

        WatchlistRecord watchlist = watchlistMapper.findByAccountId(accountId);
        if (watchlist == null) {
            watchlistMapper.createWatchlist(accountId);
            watchlist = watchlistMapper.findByAccountId(accountId);
        }
        try {
            addInstrumentInternal(accountId, watchlist, request);
        } catch (DomainException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        return getWatchlist(accountId, userUuid);
    }

    /**
     * Removes an instrument from the watchlist.
     */
    public WatchlistDto removeInstrument(Long accountId, String userUuid, Long watchlistInstrumentId) {
        // Verify the user is a client (not admin)
        verifyIsClient(userUuid);

        WatchlistRecord watchlist = watchlistMapper.findByAccountId(accountId);
        if (watchlist == null) {
            throw new IllegalArgumentException("Watchlist not found for account " + accountId);
        }

        // Verify the instrument belongs to this watchlist
        List<WatchlistInstrumentRecord> instruments = watchlistMapper.findInstrumentsByWatchlistId(watchlist.getWatchlistId());
        boolean found = instruments.stream().anyMatch(wi -> wi.getWatchlistInstrumentId().equals(watchlistInstrumentId));

        if (!found) {
            throw new IllegalArgumentException("Instrument not found in watchlist");
        }

        watchlistMapper.removeInstrument(watchlistInstrumentId);
        watchlistMapper.updateWatchlistTimestamp(watchlist.getWatchlistId());

        log.info("Removed instrument from watchlist for account {}", accountId);

        return getWatchlist(accountId, userUuid);
    }

    /**
     * Discovers, registers, quotes and stores an instrument on the given list.
     * Validates: the market-data poller has a quote for the symbol, it is not already on the list, and
     * the list is under its 25-instrument cap.
     */
    private void addInstrumentInternal(Long accountId, WatchlistRecord watchlist,
                                       AddWatchlistInstrumentRequest request) {
        if (request == null || request.symbol() == null || request.symbol().isEmpty()) {
            throw invalid("Symbol is required");
        }
        String symbol = request.symbol().trim().toUpperCase();
        log.debug("Normalized symbol: {}", symbol);

        MarketQuote fauxnanceQuote = marketData.quote(symbol).quote();
        if (fauxnanceQuote == null || fauxnanceQuote.price() == null) {
            log.warn("Instrument {} has no market data", symbol);
            throw invalid("Instrument '" + symbol + "' not found in market data");
        }

        InstrumentRecord instrument = instrumentMapper.findBySymbol(symbol);
        if (instrument == null) {
            log.info("Instrument {} not in database, registering from market data", symbol);
            instrumentMapper.upsertDiscoveredInstrument(
                    symbol,
                    symbol,
                    fauxnanceQuote.currency() != null ? fauxnanceQuote.currency() : "USD",
                    "ACTIVE"
            );
            instrument = instrumentMapper.findBySymbol(symbol);
            if (instrument == null) {
                log.error("Failed to register instrument {}", symbol);
                throw invalid("Failed to register instrument '" + symbol + "'");
            }
        }

        if (watchlistMapper.hasInstrument(watchlist.getWatchlistId(), instrument.getInstrumentId())) {
            log.warn("Instrument {} already on watchlist {}", symbol, watchlist.getWatchlistId());
            throw invalid("Instrument '" + symbol + "' is already on this watchlist");
        }

        if (watchlistMapper.countInstruments(watchlist.getWatchlistId()) >= MAX_WATCHLIST_INSTRUMENTS) {
            log.warn("Watchlist {} at capacity: {} instruments", watchlist.getWatchlistId(),
                    watchlistMapper.countInstruments(watchlist.getWatchlistId()));
            throw invalid("This watchlist is at maximum capacity (" + MAX_WATCHLIST_INSTRUMENTS + " instruments)");
        }

        if (watchlistMapper.findQuoteByInstrumentId(instrument.getInstrumentId()) == null) {
            log.info("Quote not cached for instrument {}, copying the poller's quote", symbol);
            watchlistMapper.upsertQuote(
                    instrument.getInstrumentId(),
                    symbol,
                    fauxnanceQuote.price(),
                    fauxnanceQuote.currency(),
                    fauxnanceQuote.bid(),
                    fauxnanceQuote.ask(),
                    fauxnanceQuote.change(),
                    fauxnanceQuote.changePercent(),
                    fauxnanceQuote.stale()
            );
        }

        // A fresh quote is a market-data observation: the price alerts held for this
        // symbol are checked against it exactly as they are against a Kafka feed. A
        // local environment without a broker still fires an alert the moment its quote
        // arrives through the watchlist path.
        if (fauxnanceQuote.price() != null) {
            alertService.evaluate(symbol, fauxnanceQuote.price(), fauxnanceQuote.observedAt());
        }

        watchlistMapper.addInstrument(watchlist.getWatchlistId(), instrument.getInstrumentId());
        watchlistMapper.updateWatchlistTimestamp(watchlist.getWatchlistId());
        log.info("Successfully added instrument {} to watchlist {} for account {}", symbol,
                watchlist.getWatchlistId(), accountId);
    }

    /**
     * Builds a complete WatchlistDto with instrument details and current quotes.
     */
    private WatchlistDto buildWatchlistDto(WatchlistRecord watchlist) {
        log.debug("Building WatchlistDto for watchlist: {}", watchlist.getWatchlistId());

        List<WatchlistInstrumentRecord> instrumentRecords = watchlistMapper.findInstrumentsByWatchlistId(watchlist.getWatchlistId());
        log.debug("Found {} instruments in watchlist {}", instrumentRecords.size(), watchlist.getWatchlistId());

        List<WatchlistInstrumentDto> instrumentDtos = new ArrayList<>();
        for (WatchlistInstrumentRecord wiRecord : instrumentRecords) {
            instrumentDtos.add(buildWatchlistInstrumentDto(wiRecord));
        }

        return new WatchlistDto(
                watchlist.getWatchlistId(),
                watchlist.getAccountId(),
                watchlist.getName(),
                instrumentDtos,
                watchlist.getCreatedAt(),
                watchlist.getUpdatedAt()
        );
    }

    /**
     * Builds a WatchlistInstrumentDto by joining instrument details with latest quote.
     */
    private WatchlistInstrumentDto buildWatchlistInstrumentDto(WatchlistInstrumentRecord wiRecord) {
        InstrumentRecord instrument = instrumentMapper.findById(wiRecord.getInstrumentId());
        if (instrument == null) {
            log.error("Instrument not found for instrument_id: {}", wiRecord.getInstrumentId());
            throw new IllegalArgumentException("Instrument not found: " + wiRecord.getInstrumentId());
        }

        InstrumentQuoteRecord quote = watchlistMapper.findQuoteByInstrumentId(wiRecord.getInstrumentId());

        BigDecimal currentPrice = quote != null ? quote.getCurrentPrice() : null;
        String priceCurrency = quote != null ? quote.getCurrency() : (instrument.getQuoteCurrency() != null ? instrument.getQuoteCurrency() : "USD");
        BigDecimal bid = quote != null ? quote.getBid() : null;
        BigDecimal ask = quote != null ? quote.getAsk() : null;
        BigDecimal change = quote != null ? quote.getChange() : null;
        BigDecimal changePercent = quote != null ? quote.getChangePercent() : null;
        Boolean stale = quote != null ? quote.getStale() : true;

        return new WatchlistInstrumentDto(
                wiRecord.getWatchlistInstrumentId(),
                wiRecord.getInstrumentId(),
                instrument.getSymbol(),
                instrument.getTicker(),
                instrument.getAssetClass(),
                instrument.getQuoteCurrency(),
                currentPrice,
                priceCurrency,
                bid,
                ask,
                change,
                changePercent,
                stale,
                wiRecord.getAddedAt()
        );
    }

    /**
     * Verifies that the user is a client (not an admin).
     * Throws exception if user is an admin or not found.
     */
    private void verifyIsClient(String userUuid) {
        log.debug("Verifying user is client - userUuid: {}", userUuid);
        Map<String, Object> user = userMapper.findUserByUuid(userUuid);

        if (user == null) {
            log.warn("User not found for UUID: {}", userUuid);
            throw new IllegalArgumentException("User not found");
        }

        String userType = (String) user.get("user_type");
        log.debug("User found - type: {}, uuid: {}", userType, userUuid);

        if ("admin".equals(userType)) {
            log.warn("Admin user attempted to access watchlist: {}", userUuid);
            throw new IllegalArgumentException("Watchlist is only available for client accounts");
        }

        log.debug("User verified as client: {}", userUuid);
    }

    /** The multi-list routes answer {@code ACC-403} for sessions without a client account. */
    private void verifyClientAccess(String userUuid) {
        Map<String, Object> user = userMapper.findUserByUuid(userUuid);
        if (user == null || "admin".equals(user.get("user_type"))) {
            throw new DomainException("ACC-403", "Account not accessible", HttpStatus.FORBIDDEN);
        }
    }

    /** A list the account actually owns, or {@code ACC-404} (never a hint another account has it). */
    private WatchlistRecord ownedWatchlist(Long accountId, Long watchlistId) {
        if (watchlistId == null) {
            throw notFound("Watchlist not found");
        }
        WatchlistRecord record = watchlistMapper.findByIdAndAccountId(watchlistId, accountId);
        if (record == null) {
            throw notFound("Watchlist not found");
        }
        return record;
    }

    private String normalizeName(String raw) {
        if (raw == null) {
            throw invalid("Watchlist name is required");
        }
        String name = raw.trim();
        if (name.isEmpty()) {
            throw invalid("Watchlist name is required");
        }
        if (name.length() > MAX_WATCHLIST_NAME_LENGTH) {
            throw invalid("Watchlist name must be " + MAX_WATCHLIST_NAME_LENGTH + " characters or fewer");
        }
        return name;
    }

    private void ensureNameAvailable(Long accountId, String name, Long excludeId) {
        for (WatchlistRecord existing : watchlistMapper.findAllByAccountId(accountId)) {
            if (name.equalsIgnoreCase(existing.getName()) && !existing.getWatchlistId().equals(excludeId)) {
                throw invalid("A watchlist named '" + name + "' already exists");
            }
        }
    }

    private DomainException invalid(String message) {
        return new DomainException("VAL-422", message, HttpStatus.UNPROCESSABLE_ENTITY);
    }

    private DomainException notFound(String message) {
        return new DomainException("ACC-404", message, HttpStatus.NOT_FOUND);
    }
}
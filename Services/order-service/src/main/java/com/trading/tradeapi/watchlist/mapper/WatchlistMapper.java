package com.trading.tradeapi.watchlist.mapper;

import com.trading.tradeapi.entity.InstrumentQuoteRecord;
import com.trading.tradeapi.watchlist.entity.WatchlistInstrumentRecord;
import com.trading.tradeapi.watchlist.entity.WatchlistRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface WatchlistMapper {
    // Watchlist operations
    WatchlistRecord findByAccountId(@Param("accountId") Long accountId);

    List<WatchlistRecord> findAllByAccountId(@Param("accountId") Long accountId);

    WatchlistRecord findByIdAndAccountId(@Param("watchlistId") Long watchlistId,
                                         @Param("accountId") Long accountId);

    WatchlistRecord findByAccountIdAndName(@Param("accountId") Long accountId,
                                           @Param("name") String name);

    int countByAccountId(@Param("accountId") Long accountId);

    int createWatchlist(@Param("accountId") Long accountId);

    int createNamedWatchlist(@Param("accountId") Long accountId, @Param("name") String name);

    int renameWatchlist(@Param("watchlistId") Long watchlistId, @Param("name") String name);

    int deleteWatchlist(@Param("watchlistId") Long watchlistId);

    int updateWatchlistTimestamp(@Param("watchlistId") Long watchlistId);

    // Watchlist instrument operations
    List<WatchlistInstrumentRecord> findInstrumentsByWatchlistId(@Param("watchlistId") Long watchlistId);

    WatchlistInstrumentRecord findInstrumentByIdAndAccount(@Param("watchlistInstrumentId") Long watchlistInstrumentId,
                                                           @Param("accountId") Long accountId);

    int addInstrument(@Param("watchlistId") Long watchlistId, @Param("instrumentId") Long instrumentId);

    int removeInstrument(@Param("watchlistInstrumentId") Long watchlistInstrumentId);

    int countInstruments(@Param("watchlistId") Long watchlistId);

    boolean hasInstrument(@Param("watchlistId") Long watchlistId, @Param("instrumentId") Long instrumentId);

    // Instrument quote operations
    InstrumentQuoteRecord findQuoteByInstrumentId(@Param("instrumentId") Long instrumentId);

    int upsertQuote(@Param("instrumentId") Long instrumentId,
                    @Param("symbol") String symbol,
                    @Param("currentPrice") java.math.BigDecimal currentPrice,
                    @Param("currency") String currency,
                    @Param("bid") java.math.BigDecimal bid,
                    @Param("ask") java.math.BigDecimal ask,
                    @Param("change") java.math.BigDecimal change,
                    @Param("changePercent") java.math.BigDecimal changePercent,
                    @Param("stale") Boolean stale);

    List<Long> findAllWatchlistInstrumentIds();
}

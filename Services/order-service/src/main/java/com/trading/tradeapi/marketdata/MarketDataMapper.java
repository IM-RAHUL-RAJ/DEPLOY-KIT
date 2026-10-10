package com.trading.tradeapi.marketdata;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Collection;
import java.util.List;

/** Reads what the market-data poller stored, and records what is wanted from it. */
@Mapper
public interface MarketDataMapper {

    MarketQuote findQuote(@Param("symbol") String symbol);

    List<MarketQuote> findQuotes(@Param("symbols") Collection<String> symbols);

    MarketCandles findCandles(@Param("symbol") String symbol);

    /** Extends existing interest in a symbol; never shortens it. Returns rows updated. */
    int extendInterest(@Param("symbol") String symbol,
                       @Param("reason") String reason,
                       @Param("ttlMinutes") int ttlMinutes);

    int insertInterest(@Param("symbol") String symbol,
                       @Param("reason") String reason,
                       @Param("ttlMinutes") int ttlMinutes);

    /** Whether the poller recently found that Fauxnance has no such symbol. */
    boolean isNotFound(@Param("symbol") String symbol,
                       @Param("reason") String reason,
                       @Param("withinMinutes") int withinMinutes);
}

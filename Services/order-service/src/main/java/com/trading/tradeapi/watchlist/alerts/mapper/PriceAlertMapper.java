package com.trading.tradeapi.watchlist.alerts.mapper;

import com.trading.tradeapi.watchlist.alerts.entity.PriceAlertRecord;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * SQL for the price alert table. The mutating updates are guarded: each one contains the
 * current status in its WHERE clause, so a replay of the same market-data message, or a
 * double cancel, touches no row and returns 0.
 */
public interface PriceAlertMapper {

    List<PriceAlertRecord> findActiveBySymbol(@Param("symbol") String symbol);

    PriceAlertRecord findById(@Param("priceAlertId") Long priceAlertId);

    PriceAlertRecord findActiveByAccountSymbolDirectionThreshold(
            @Param("accountId") Long accountId,
            @Param("symbol") String symbol,
            @Param("direction") String direction,
            @Param("threshold") BigDecimal threshold);

    List<PriceAlertRecord> findByAccount(@Param("accountId") Long accountId);

    int countActiveByAccount(@Param("accountId") Long accountId);

    int insertActive(PriceAlertRecord alert);

    int markTriggered(@Param("priceAlertId") Long priceAlertId,
                      @Param("triggeredPrice") BigDecimal triggeredPrice,
                      @Param("triggeredAt") Instant triggeredAt);

    int markCancelled(@Param("priceAlertId") Long priceAlertId,
                      @Param("accountId") Long accountId);
}
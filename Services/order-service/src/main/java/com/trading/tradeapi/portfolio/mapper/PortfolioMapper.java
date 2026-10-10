package com.trading.tradeapi.portfolio.mapper;

import com.trading.tradeapi.portfolio.entity.FilledOrderRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * Read-only access to the fill history used by the realised P&amp;L walk. A dedicated query rather
 * than a bean onto the order history because the history DTO deliberately omits fill prices.
 */
@Mapper
public interface PortfolioMapper {

    List<FilledOrderRow> findFilledOrdersByAccountId(@Param("accountId") long accountId);

    List<FilledOrderRow> findFilledOrdersByAccountIdBetween(
            @Param("accountId") long accountId,
            @Param("executedOn") java.time.Instant executedOn);
}
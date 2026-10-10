package com.trading.tradeapi.strategy.mapper;

import com.trading.tradeapi.strategy.entity.StrategyExecutionRecord;
import com.trading.tradeapi.strategy.entity.StrategyRecord;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * SQL for strategies and their executions. Status changes are guarded by the current
 * status, and every customer-facing write also by the account, so a strategy can only
 * be changed by its owner.
 */
public interface StrategyMapper {

    List<StrategyRecord> findActiveBySymbol(@Param("symbol") String symbol);

    /** Every symbol with at least one active strategy. */
    List<String> findActiveSymbols();

    /** An active price target identical to the one being created, for idempotent creation. */
    StrategyRecord findActiveTarget(@Param("accountId") Long accountId, @Param("symbol") String symbol,
                                    @Param("side") String side, @Param("targetPrice") java.math.BigDecimal targetPrice,
                                    @Param("quantity") Long quantity);

    StrategyRecord findById(@Param("strategyId") Long strategyId);

    StrategyRecord findByIdAndAccount(@Param("strategyId") Long strategyId, @Param("accountId") Long accountId);

    List<StrategyRecord> findByAccount(@Param("accountId") Long accountId);

    int countByAccount(@Param("accountId") Long accountId);

    int insert(StrategyRecord strategy);

    /** Pause or resume; a strategy stopped by the platform is resumed the same way. */
    int updateStatus(@Param("strategyId") Long strategyId, @Param("accountId") Long accountId,
                     @Param("status") String status);

    int delete(@Param("strategyId") Long strategyId, @Param("accountId") Long accountId);

    int recordEvaluation(@Param("strategyId") Long strategyId, @Param("state") String state,
                         @Param("price") BigDecimal price, @Param("at") Instant at);

    int resetFailures(@Param("strategyId") Long strategyId);

    int incrementFailures(@Param("strategyId") Long strategyId);

    /** Stops an ACTIVE strategy; 0 when it was already paused or stopped. */
    int stop(@Param("strategyId") Long strategyId, @Param("reason") String reason);

    /** Marks a one-shot strategy done; 0 when it was no longer ACTIVE. */
    int complete(@Param("strategyId") Long strategyId, @Param("reason") String reason);

    int insertExecution(StrategyExecutionRecord execution);

    int completeExecution(@Param("executionId") Long executionId, @Param("status") String status,
                          @Param("orderId") String orderId, @Param("reason") String reason);

    int countPlacedSince(@Param("strategyId") Long strategyId, @Param("since") Instant since);

    /** The order (as ORD-n) placed under this exact idempotency key, or null when there is none. */
    String findOrderIdByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    /** The latest order (as ORD-n) any decision of this strategy placed, or null. */
    String findLatestOrderIdForStrategy(@Param("strategyId") Long strategyId);

    List<StrategyExecutionRecord> findExecutions(@Param("strategyId") Long strategyId, @Param("limit") int limit);
}

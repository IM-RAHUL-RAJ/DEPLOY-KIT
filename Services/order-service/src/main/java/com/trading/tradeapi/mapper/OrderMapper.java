package com.trading.tradeapi.mapper;

import com.trading.tradeapi.dto.OrderHistoryEntryDto;
import com.trading.tradeapi.entity.OrderRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.Instant;
import java.util.List;

@Mapper
public interface OrderMapper {
    int insertOrder(OrderRecord order);

    OrderRecord findByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

    OrderRecord findById(@Param("id") String id);

    int cancelOrder(@Param("id") String id);

    List<OrderHistoryEntryDto> findByAccountAndFilters(@Param("accountId") Long accountId,
                                                      @Param("status") String status,
                                                      @Param("from") Instant from,
                                                      @Param("to") Instant to);
}

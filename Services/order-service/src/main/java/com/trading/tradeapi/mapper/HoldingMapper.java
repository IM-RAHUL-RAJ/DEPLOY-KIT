package com.trading.tradeapi.mapper;

import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.entity.HoldingRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface HoldingMapper {
    HoldingRecord findByAccountAndTicker(@Param("accountId") Long accountId,
                                         @Param("ticker") String ticker);

    int insertHolding(HoldingRecord holding);

    int updateHolding(HoldingRecord holding);

    int deleteHolding(@Param("holdingId") Long holdingId);

    List<PositionResponseDto> findHoldingsByAccountId(@Param("accountId") Long accountId);
}

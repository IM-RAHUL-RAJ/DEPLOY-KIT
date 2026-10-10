package com.trading.tradeapi.mapper;

import com.trading.tradeapi.entity.InstrumentRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface InstrumentMapper {
    InstrumentRecord findBySymbol(@Param("symbol") String symbol);

    InstrumentRecord findById(@Param("id") Long id);

    int upsertDiscoveredInstrument(@Param("symbol") String symbol,
                                   @Param("ticker") String ticker,
                                   @Param("quoteCurrency") String quoteCurrency,
                                   @Param("status") String status);
}

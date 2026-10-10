package com.trading.tradeapi.mapper;

import com.trading.tradeapi.entity.AccountRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

@Mapper
public interface AccountMapper {
    AccountRecord findById(@Param("id") Long id);

    int updateCashBalanceAndVersion(@Param("id") Long id,
                                   @Param("cashBalance") BigDecimal cashBalance,
                                   @Param("version") Long version);
}

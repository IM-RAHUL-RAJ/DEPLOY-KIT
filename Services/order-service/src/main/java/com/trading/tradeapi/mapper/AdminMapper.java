package com.trading.tradeapi.mapper;

import com.trading.tradeapi.dto.AdminAccountDto;
import com.trading.tradeapi.dto.AdminOrderDto;
import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.dto.InstrumentDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface AdminMapper {

    long countUsers();

    List<AdminUserDto> findAllUsers();

    AdminUserDto findUserById(@Param("userId") Long userId);

    /** Resolves a JWT subject to a user row so status changes can be attributed. */
    Long findUserIdByUuid(@Param("uuid") String uuid);

    List<AdminUserDto> findUsersByStatus(@Param("status") String status);

    long countUsersByStatus(@Param("status") String status);

    /** Moves the user to a new lifecycle state and stamps the matching audit columns. */
    int updateUserStatus(@Param("userId") Long userId,
                         @Param("newStatus") String newStatus,
                         @Param("actorUserId") Long actorUserId,
                         @Param("reason") String reason);

    int logStatusChange(@Param("userId") Long userId,
                        @Param("oldStatus") String oldStatus,
                        @Param("newStatus") String newStatus,
                        @Param("reason") String reason,
                        @Param("actorUserId") Long actorUserId);

    long countAccounts();

    long countAccountsByStatus(@Param("status") String status);

    BigDecimal sumCashBalance();

    long countInstruments();

    long countOrders();

    long countOrdersByStatus(@Param("status") String status);

    List<AdminAccountDto> findAllAccounts();

    AdminAccountDto findAccountById(@Param("accountId") Long accountId);

    int updateAccountStatus(@Param("accountId") Long accountId, @Param("status") String status);

    /** Adds the amount only while the result stays non-negative; 0 rows means it would overdraw. */
    int addBalanceIfNotOverdrawn(@Param("accountId") Long accountId, @Param("amount") BigDecimal amount);

    int logBalanceAdjustment(@Param("accountId") Long accountId,
                             @Param("amount") BigDecimal amount,
                             @Param("reason") String reason,
                             @Param("actorUserId") Long actorUserId);

    List<AdminOrderDto> findAllOrders(@Param("status") String status,
                                      @Param("accountId") Long accountId,
                                      @Param("limit") Integer limit);

    List<InstrumentDto> findAllInstruments();

    int insertInstrument(@Param("symbol") String symbol,
                         @Param("ticker") String ticker,
                         @Param("assetClass") String assetClass,
                         @Param("quoteCurrency") String quoteCurrency,
                         @Param("status") String status);

    int updateInstrumentStatus(@Param("instrumentId") Long instrumentId, @Param("status") String status);
}

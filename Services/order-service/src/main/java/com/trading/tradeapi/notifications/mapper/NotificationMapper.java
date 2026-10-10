package com.trading.tradeapi.notifications.mapper;

import com.trading.tradeapi.notifications.entity.NotificationRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface NotificationMapper {

    /**
     * Orders that reached an outcome since {@code since} and have no notification yet, oldest
     * first: neither one recorded under the shared outcome key nor an older one whose body
     * names the order (rows written before the key existed).
     */
    java.util.List<java.util.Map<String, Object>> findUnnotifiedOutcomes(@Param("since") java.time.Instant since,
                                                                         @Param("limit") int limit);

    NotificationRecord findById(@Param("notificationId") Long notificationId);

    NotificationRecord findByEventId(@Param("eventId") String eventId);

    int insert(NotificationRecord record);

    int updateStatus(@Param("notificationId") Long notificationId, @Param("status") String status);

    int markRead(@Param("notificationId") Long notificationId, @Param("accountId") Long accountId);

    List<NotificationRecord> findByAccount(@Param("accountId") Long accountId,
                                           @Param("unreadOnly") boolean unreadOnly,
                                           @Param("limit") int limit);
}
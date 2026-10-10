package com.trading.tradeapi.preferences.mapper;

import com.trading.tradeapi.preferences.entity.CustomerPreferenceRecord;
import com.trading.tradeapi.preferences.entity.NotificationPreferenceRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * The only code that touches {@code customer_preference} and {@code notification_preference}.
 * Nothing outside the preferences package imports this class, which is what keeps the boundary
 * a boundary: another module reading these tables directly would be the same design mistake
 * with a shorter stack trace.
 */
@Mapper
public interface PreferencesMapper {

    /** {@code null} when the customer has never saved a preference. That is not an error. */
    CustomerPreferenceRecord findByAccountId(@Param("accountId") Long accountId);

    int updateTheme(@Param("accountId") Long accountId, @Param("theme") String theme);

    int insertTheme(@Param("accountId") Long accountId, @Param("theme") String theme);

    int insert(@Param("accountId") Long accountId,
               @Param("defaultAccountId") Long defaultAccountId,
               @Param("alertChannel") String alertChannel,
               @Param("alertContact") String alertContact,
               @Param("displayCurrency") String displayCurrency);

    int update(@Param("accountId") Long accountId,
               @Param("defaultAccountId") Long defaultAccountId,
               @Param("alertChannel") String alertChannel,
               @Param("alertContact") String alertContact,
               @Param("displayCurrency") String displayCurrency);

    /** The per-category row, or {@code null} when the account has none for that category. */
    NotificationPreferenceRecord findNotificationPreference(@Param("accountId") Long accountId,
                                                            @Param("category") String category);

    List<NotificationPreferenceRecord> findNotificationPreferences(@Param("accountId") Long accountId);

    int insertNotificationPreference(@Param("accountId") Long accountId,
                                     @Param("category") String category,
                                     @Param("channel") String channel,
                                     @Param("contact") String contact);

    int updateNotificationPreference(@Param("accountId") Long accountId,
                                     @Param("category") String category,
                                     @Param("channel") String channel,
                                     @Param("contact") String contact);
}

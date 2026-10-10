package com.trading.tradeapi.notifications.service.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The in-app (push) provider. The browser shows in-app notifications from the inbox, so no
 * device is contacted; what stays real is the {@code SENT}/{@code FAILED} record of what happened.
 */
@Component
public class SimulatedSenders implements PushSender {

    private static final Logger logger = LoggerFactory.getLogger(SimulatedSenders.class);

    @Override
    public boolean deliverPush(String deviceToken, String body) {
        logger.debug("Simulated push delivery accepted for [{}]",
                deviceToken == null || deviceToken.isBlank() ? "unset" : "device-" + deviceToken.length());
        return true;
    }
}
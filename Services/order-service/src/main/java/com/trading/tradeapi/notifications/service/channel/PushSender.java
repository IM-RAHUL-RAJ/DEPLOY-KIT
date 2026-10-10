package com.trading.tradeapi.notifications.service.channel;

/**
 * The push provider seam. The notifications module routes through this interface exactly as it
 * would through a real provider; this build ships the {@link SimulatedSenders} implementation.
 */
public interface PushSender {

    /**
     * @param deviceToken an opaque destination, valid only to the push provider
     * @param body        the message text
     * @return true when the provider accepted the message
     */
    boolean deliverPush(String deviceToken, String body);
}
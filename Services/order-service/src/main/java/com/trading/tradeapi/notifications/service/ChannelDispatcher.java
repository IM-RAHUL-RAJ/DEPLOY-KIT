package com.trading.tradeapi.notifications.service;

import com.trading.tradeapi.notifications.service.channel.PushSender;
import com.trading.tradeapi.service.EmailConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Turns a resolved channel into one real attempt on a provider.
 *
 * <p>Email goes through the service's mail stack. In-app (push) goes through the
 * {@link PushSender} seam. Every method returns a
 * boolean and never throws, so an outage on one message cannot take the consumer thread down.
 */
@Component
public class ChannelDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ChannelDispatcher.class);

    private final JavaMailSender mailSender;
    private final EmailConfigurationService emailConfig;
    private final PushSender pushSender;

    public ChannelDispatcher(Optional<JavaMailSender> mailSender,
                             EmailConfigurationService emailConfig,
                             PushSender pushSender) {
        this.mailSender = mailSender.orElse(null);
        this.emailConfig = emailConfig;
        this.pushSender = pushSender;
    }

    public boolean sendEmail(String to, String title, String body) {
        if (!emailConfig.isConfigured() || mailSender == null) {
            logger.warn("Email not configured; notification '{}' for [{}] was not sent", title, mask(to));
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailConfig.getFromAddress());
            message.setTo(to);
            message.setSubject("Akatsuki Terminal: " + title);
            message.setText(body);
            mailSender.send(message);
            return true;
        } catch (MailException e) {
            logger.warn("Mail send failed for '{}': {}", title, e.getMessage());
            return false;
        }
    }

    public boolean sendPush(String to, String body) {
        return pushSender.deliverPush(to, body);
    }

    /** Never writes a full contact to the log. */
    private String mask(String contact) {
        if (contact == null || contact.isEmpty()) {
            return "unknown";
        }
        return contact.charAt(0) + "***";
    }
}
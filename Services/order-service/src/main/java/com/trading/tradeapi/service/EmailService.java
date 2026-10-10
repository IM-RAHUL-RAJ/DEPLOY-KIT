package com.trading.tradeapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Tells a user about an administrator's decision on their account: approved,
 * suspended or reinstated.
 *
 * <p>JavaMailSender is optional. Without SMTP configured, each notice is logged
 * instead and the send reports {@code false}, so the admin console can say that
 * no email went out rather than implying one did.
 */
@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private static final String SIGN_OFF = "Best regards,\nAkatsuki Terminal Support Team";

    private final JavaMailSender mailSender;
    private final EmailConfigurationService emailConfig;

    public EmailService(Optional<JavaMailSender> mailSender, EmailConfigurationService emailConfig) {
        this.mailSender = mailSender.orElse(null);
        this.emailConfig = emailConfig;
    }

    /**
     * Tells an applicant their account has been approved and created, and is ready
     * to sign in to.
     *
     * @param accountId the trading account that was opened, or null if unknown
     * @param currency  the account's ledger currency, or null if unknown
     * @return true if the email left the service
     */
    public boolean sendAccountApprovedEmail(String email, String userName, String username,
                                            Long accountId, String currency) {
        StringBuilder body = new StringBuilder()
                .append("Hello ").append(userName).append(",\n\n")
                .append("Good news: your Akatsuki Terminal account has been approved and created.\n\n");
        if (accountId != null) {
            body.append("Trading account: #").append(accountId);
            if (currency != null && !currency.isBlank()) {
                body.append(" (").append(currency).append(")");
            }
            body.append("\n");
        }
        if (username != null && !username.isBlank()) {
            body.append("Username: ").append(username).append("\n");
        }
        body.append("\nYou can now sign in with the password you chose when you registered. ")
                .append("Your account opens with no cash: an administrator adds funds before your first trade.\n\n")
                .append(SIGN_OFF);
        return send(email, "Your Akatsuki Terminal Account Has Been Approved", body.toString(), "approval");
    }

    /** Tells a user their account has been suspended, and why. */
    public boolean sendAccountSuspensionEmail(String email, String userName, String reason) {
        String body = "Hello " + userName + ",\n\n"
                + "Your Akatsuki Terminal trading account has been suspended.\n\n"
                + "Reason: " + reason + "\n\n"
                + "You will not be able to place new orders until your account is reinstated. "
                + "Please contact support for more information or to appeal this decision.\n\n"
                + SIGN_OFF;
        return send(email, "Your Akatsuki Terminal Account Has Been Suspended", body, "suspension");
    }

    /** Tells a user their suspended account is active again. */
    public boolean sendAccountReinstatedEmail(String email, String userName, String reason) {
        String body = "Hello " + userName + ",\n\n"
                + "Your Akatsuki Terminal trading account has been reinstated.\n\n"
                + "Reason: " + reason + "\n\n"
                + "You can now resume trading. If you have any questions, please contact support.\n\n"
                + SIGN_OFF;
        return send(email, "Your Akatsuki Terminal Account Has Been Reinstated", body, "reinstatement");
    }

    /** Sends one notice; never throws, so a mail problem cannot undo an admin decision. */
    private boolean send(String to, String subject, String body, String kind) {
        if (!emailConfig.isConfigured() || mailSender == null) {
            logger.warn("Email service not configured. The {} notice for {} was not sent.", kind, maskEmail(to));
            return false;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(emailConfig.getFromAddress());
            message.setTo(to);
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
            logger.info("Account {} notice sent to {}", kind, maskEmail(to));
            return true;
        } catch (Exception e) {
            logger.error("Failed to send {} email to {}: {}: {}",
                    kind, maskEmail(to), e.getClass().getSimpleName(), e.getMessage());
            return false;
        }
    }

    /** Never writes a full email address to the log. */
    private String maskEmail(String email) {
        if (email == null || email.isEmpty()) {
            return "unknown";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 1) {
            return email;
        }
        return email.charAt(0) + "***" + email.substring(atIndex);
    }
}

package com.trading.tradeapi.admin;

import com.trading.tradeapi.dto.AdminUserDto;
import com.trading.tradeapi.service.EmailService;
import org.springframework.stereotype.Component;

/**
 * Tells a user about an administrator's decision on their account.
 *
 * <p>Called by the controller once the decision's transaction has committed, so
 * the email describes something that has actually happened and a slow or failing
 * mail server can neither hold a database lock nor roll the decision back.
 */
@Component
public class AdminNotifications {

    private final EmailService emailService;

    public AdminNotifications(EmailService emailService) {
        this.emailService = emailService;
    }

    /**
     * Tells an applicant their account has been approved and created. Returns the
     * user with emailDelivered set to whether the email left the service.
     */
    public AdminUserDto approved(AdminUserDto user) {
        if (!hasEmail(user)) {
            return user.withEmailDelivered(false);
        }
        return user.withEmailDelivered(emailService.sendAccountApprovedEmail(
                user.email(), greetingName(user), user.username(), user.accountId(), user.currency()));
    }

    /** Returns the user with emailDelivered set to whether the email left the service. */
    public AdminUserDto suspended(AdminUserDto user, String reason) {
        if (!hasEmail(user)) {
            return user.withEmailDelivered(false);
        }
        return user.withEmailDelivered(
                emailService.sendAccountSuspensionEmail(user.email(), greetingName(user), reason));
    }

    public AdminUserDto reinstated(AdminUserDto user, String reason) {
        if (!hasEmail(user)) {
            return user.withEmailDelivered(false);
        }
        return user.withEmailDelivered(
                emailService.sendAccountReinstatedEmail(user.email(), greetingName(user), reason));
    }

    private static boolean hasEmail(AdminUserDto user) {
        return user.email() != null && !user.email().isBlank();
    }

    private static String greetingName(AdminUserDto user) {
        return user.firstName() != null && !user.firstName().isBlank()
                ? user.firstName()
                : user.username();
    }
}

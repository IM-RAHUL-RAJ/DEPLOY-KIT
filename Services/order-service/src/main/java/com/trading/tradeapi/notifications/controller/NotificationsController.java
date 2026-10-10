package com.trading.tradeapi.notifications.controller;

import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.notifications.dto.NotificationDto;
import com.trading.tradeapi.notifications.service.NotificationService;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The customer-facing inbox. The bearer token has already been verified by
 * {@code JwtAuthenticationFilter}; what verification cannot decide is whether this caller may
 * read these notifications, which is {@link #requireOwnedAccount} on every route.
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationsController {

    private static final Logger log = LoggerFactory.getLogger(NotificationsController.class);

    private final NotificationService notificationService;

    public NotificationsController(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @GetMapping("/{accountId}")
    public List<NotificationDto> list(@PathVariable("accountId") Long accountId,
                                      @RequestParam(value = "unread", required = false) Boolean unread,
                                      @RequestParam(value = "limit", required = false) Integer limit,
                                      HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return notificationService.listFor(accountId, unread, limit);
    }

    @PostMapping("/{accountId}/{notificationId}/read")
    public ResponseEntity<NotificationDto> markRead(@PathVariable("accountId") Long accountId,
                                                    @PathVariable("notificationId") Long notificationId,
                                                    HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return ResponseEntity.ok(notificationService.markRead(accountId, notificationId));
    }

    /**
     * The path account must be the account in the verified token, exactly as in the preferences
     * module: a mismatch is {@code 403 ACC-403}, not {@code 404}, so a probe cannot learn
     * anything about an account it was never allowed to see.
     */
    private void requireOwnedAccount(Long accountId, HttpServletRequest request) {
        Long authenticatedAccountId = (Long) request
                .getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);

        if (authenticatedAccountId == null) {
            log.warn("Notifications route reached without an account claim: accountId={}", accountId);
            throw new UnauthorizedAccountAccessException();
        }
        if (!authenticatedAccountId.equals(accountId)) {
            log.warn("Cross-account notifications access refused: claim={} path={}",
                    authenticatedAccountId, accountId);
            throw new UnauthorizedAccountAccessException();
        }
    }
}
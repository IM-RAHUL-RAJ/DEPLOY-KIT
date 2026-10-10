package com.trading.tradeapi.preferences.controller;

import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesPutRequest;
import com.trading.tradeapi.preferences.dto.NotificationPreferencesResponse;
import com.trading.tradeapi.preferences.service.NotificationPreferenceService;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How one account wants each kind of notification delivered. Same ownership rule as the rest of
 * the preferences routes: the path account must be the account in the verified token, and a
 * mismatch is {@code 403} rather than {@code 404} so a customer cannot probe which ids exist.
 */
@RestController
@RequestMapping("/api/v1/preferences/notifications")
public class NotificationPreferenceController {

    private static final Logger log = LoggerFactory.getLogger(NotificationPreferenceController.class);

    private final NotificationPreferenceService notificationPreferenceService;

    public NotificationPreferenceController(NotificationPreferenceService notificationPreferenceService) {
        this.notificationPreferenceService = notificationPreferenceService;
    }

    @GetMapping("/{accountId}")
    public NotificationPreferencesResponse get(@PathVariable("accountId") Long accountId,
                                               HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return notificationPreferenceService.get(accountId);
    }

    @PutMapping("/{accountId}")
    public NotificationPreferencesResponse put(@PathVariable("accountId") Long accountId,
                                               @Valid @RequestBody NotificationPreferencesPutRequest body,
                                               HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return notificationPreferenceService.put(accountId, body);
    }

    private void requireOwnedAccount(Long accountId, HttpServletRequest request) {
        Long authenticatedAccountId = (Long) request
                .getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);

        if (authenticatedAccountId == null) {
            log.warn("Notification preference route reached without an account claim: accountId={}", accountId);
            throw new UnauthorizedAccountAccessException();
        }
        if (!authenticatedAccountId.equals(accountId)) {
            log.warn("Cross-account notification preference access refused: claim={} path={}",
                    authenticatedAccountId, accountId);
            throw new UnauthorizedAccountAccessException();
        }
    }
}
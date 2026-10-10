package com.trading.tradeapi.preferences.controller;

import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.preferences.dto.PreferenceRequest;
import com.trading.tradeapi.preferences.dto.PreferenceResponse;
import com.trading.tradeapi.preferences.service.PreferencesService;
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
 * The customer-facing routes of the preferences module. The bearer token has already been
 * verified by {@code JwtAuthenticationFilter} before any handler here runs; what verification
 * cannot decide is whether this caller may touch this preference, which is
 * {@link #requireOwnedAccount} on every route.
 *
 * <p>Resolution is deliberately absent from this controller. What the notification module
 * calls to find a channel is {@code preferences.api.AlertChannelResolver}, a Java interface —
 * see that type for why it never becomes a route.
 */
@RestController
@RequestMapping("/api/v1/preferences")
public class PreferencesController {

    private static final Logger log = LoggerFactory.getLogger(PreferencesController.class);

    private final PreferencesService preferencesService;

    public PreferencesController(PreferencesService preferencesService) {
        this.preferencesService = preferencesService;
    }

    @GetMapping("/{accountId}")
    public PreferenceResponse get(@PathVariable("accountId") Long accountId, HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return preferencesService.get(accountId);
    }

    @PutMapping("/{accountId}")
    public PreferenceResponse put(@PathVariable("accountId") Long accountId,
                                  @Valid @RequestBody PreferenceRequest body,
                                  HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return preferencesService.put(accountId, body);
    }

    /**
     * The path account must be the account in the verified token.
     *
     * <p>A mismatch is {@code 403 ACC-403} and never {@code 404}: a customer probing another
     * customer's preferences is an access-control failure, and answering {@code 404} would let
     * an attacker distinguish "not yours" from "does not exist". A token with no account claim
     * is an administrator, who has no preference of their own — {@code 403} rather than
     * {@code 401}, because the session is valid and a {@code 401} would sign the client out.
     */
    /** The colour theme, saved on its own: {@code {"theme": "dark" | "light"}}. */
    @PutMapping("/{accountId}/theme")
    public PreferenceResponse putTheme(@PathVariable("accountId") Long accountId,
                                       @org.springframework.web.bind.annotation.RequestBody java.util.Map<String, String> body,
                                       HttpServletRequest request) {
        requireOwnedAccount(accountId, request);
        return preferencesService.putTheme(accountId, body == null ? null : body.get("theme"));
    }

    private void requireOwnedAccount(Long accountId, HttpServletRequest request) {
        Long authenticatedAccountId = (Long) request
                .getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);

        if (authenticatedAccountId == null) {
            log.warn("Preferences route reached without an account claim: accountId={}", accountId);
            throw new UnauthorizedAccountAccessException();
        }
        if (!authenticatedAccountId.equals(accountId)) {
            log.warn("Cross-account preferences access refused: claim={} path={}",
                    authenticatedAccountId, accountId);
            throw new UnauthorizedAccountAccessException();
        }
    }
}

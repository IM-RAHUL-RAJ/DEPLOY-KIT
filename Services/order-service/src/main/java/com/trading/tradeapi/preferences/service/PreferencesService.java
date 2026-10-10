package com.trading.tradeapi.preferences.service;

import com.trading.tradeapi.entity.AccountRecord;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.dto.PreferenceRequest;
import com.trading.tradeapi.preferences.dto.PreferenceResponse;
import com.trading.tradeapi.preferences.entity.CustomerPreferenceRecord;
import com.trading.tradeapi.preferences.mapper.PreferencesMapper;
import com.trading.tradeapi.service.AccountService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Owns {@code customer_preference}. Reads account data through {@link AccountService} rather
 * than through a second query of its own, because the accounts table already has an owner and
 * a preference row must never become a second place where the same fact is stored.
 */
@Service
public class PreferencesService {

    private static final Logger log = LoggerFactory.getLogger(PreferencesService.class);

    /** Deliberately loose: it has to reject obvious rubbish, not win an RFC argument. */
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");

    private final PreferencesMapper preferencesMapper;
    private final AccountService accountService;

    public PreferencesService(PreferencesMapper preferencesMapper, AccountService accountService) {
        this.preferencesMapper = preferencesMapper;
        this.accountService = accountService;
    }

    /**
     * Reads the preference, or the shape of an unset one when nothing has been saved.
     * An unset preference is a {@code 200}, never a {@code 404}: the caller has to be able to
     * tell "this customer never answered" from "this request failed".
     */
    @Transactional(readOnly = true)
    public PreferenceResponse get(long accountId) {
        accountService.getAccountRecord(accountId);
        return toResponse(accountId, preferencesMapper.findByAccountId(accountId));
    }

    /** Whole-record replace. Second {@code PUT} for an account replaces the first. */
    @Transactional
    public PreferenceResponse put(long accountId, PreferenceRequest request) {
        AccountRecord account = accountService.getAccountRecord(accountId);

        AlertChannel channel = request.alertChannel();
        String contact = normaliseContact(channel, request.alertContact());
        String currency = normaliseCurrency(request.displayCurrency());
        Long defaultAccountId = resolveDefaultAccount(account, request.defaultAccountId());

        CustomerPreferenceRecord existing = preferencesMapper.findByAccountId(accountId);
        if (existing == null) {
            preferencesMapper.insert(accountId, defaultAccountId, channel.name(), contact, currency);
        } else {
            preferencesMapper.update(accountId, defaultAccountId, channel.name(), contact, currency);
        }

        // Logged without the contact: an email address or a telephone number is personal data
        // and this line ends up in a file somebody will later paste into a ticket.
        log.info("Preferences saved for account {}: channel={}", accountId, channel);

        CustomerPreferenceRecord saved = preferencesMapper.findByAccountId(accountId);
        return new PreferenceResponse(accountId, defaultAccountId, channel, contact, currency,
                themeOf(saved), saved.getUpdatedAt());
    }

    /**
     * The default account must exist and belong to the same user as the account being written.
     * A body naming somebody else's account is the same probe as a path naming it, so it is an
     * access-control failure and it is logged as one.
     */
    private Long resolveDefaultAccount(AccountRecord account, Long requested) {
        if (requested == null) {
            return null;
        }
        AccountRecord target;
        try {
            target = accountService.getAccountRecord(requested);
        } catch (AccountNotFoundException e) {
            throw new DomainException("VAL-422", "defaultAccountId does not name an account",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!account.getUserId().equals(target.getUserId())) {
            log.warn("Cross-account default account rejected: account {} asked for account {}",
                    account.getAccountId(), requested);
            throw new UnauthorizedAccountAccessException();
        }
        return requested;
    }

    /**
     * Delivery is in-app ({@code PUSH}), email, or both. In-app needs no destination, so any
     * contact sent with it is dropped; email and both need an email address.
     */
    private String normaliseContact(AlertChannel channel, String contact) {
        String trimmed = contact == null ? null : contact.trim();
        boolean needsEmail = channel == AlertChannel.EMAIL || channel == AlertChannel.BOTH;
        if (!needsEmail) {
            return null;
        }
        if (trimmed == null || trimmed.isEmpty()) {
            throw new DomainException("VAL-422", "An email address is required for " + label(channel),
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (!EMAIL.matcher(trimmed).matches()) {
            throw new DomainException("VAL-422", "That is not an email address",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return trimmed;
    }

    private static String label(AlertChannel channel) {
        return channel == AlertChannel.BOTH ? "email and in-app delivery" : "email delivery";
    }

    /** Saves the colour theme on its own; the rest of the preference is untouched. */
    @Transactional
    public PreferenceResponse putTheme(long accountId, String theme) {
        accountService.getAccountRecord(accountId);
        String value = theme == null ? "" : theme.trim().toLowerCase(Locale.ROOT);
        if (!value.equals("dark") && !value.equals("light")) {
            throw new DomainException("VAL-422", "theme must be dark or light", HttpStatus.UNPROCESSABLE_ENTITY);
        }
        if (preferencesMapper.updateTheme(accountId, value) == 0) {
            preferencesMapper.insertTheme(accountId, value);
        }
        log.info("Theme saved for account {}: {}", accountId, value);
        return toResponse(accountId, preferencesMapper.findByAccountId(accountId));
    }

    static final String DEFAULT_THEME = "dark";

    private static String themeOf(CustomerPreferenceRecord record) {
        return record == null || record.getTheme() == null ? DEFAULT_THEME : record.getTheme();
    }

    private String normaliseCurrency(String currency) {
        if (currency == null || currency.isBlank()) {
            return null;
        }
        String upper = currency.trim().toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(upper).matches()) {
            throw new DomainException("VAL-422", "displayCurrency must be a three-letter ISO 4217 code",
                    HttpStatus.UNPROCESSABLE_ENTITY);
        }
        return upper;
    }

    private PreferenceResponse toResponse(long accountId, CustomerPreferenceRecord record) {
        if (record == null) {
            return new PreferenceResponse(accountId, null, AlertChannel.NONE, null, null, DEFAULT_THEME, null);
        }
        return new PreferenceResponse(
                record.getAccountId(),
                record.getDefaultAccountId(),
                AlertChannel.fromStorage(record.getAlertChannel()),
                record.getAlertContact(),
                record.getDisplayCurrency(),
                themeOf(record),
                record.getUpdatedAt());
    }
}

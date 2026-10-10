import { Injectable, inject, signal } from '@angular/core';
import { TradeService } from './trade.service';
import { TokenService } from './token.service';
import { CurrencyService } from './currency.service';
import { ThemeService, type Theme } from './theme.service';
import { PreferencesService, type PreferenceRequest, type PreferenceResponse } from './preferences.service';

/**
 * Where a stored preference stops being a row and becomes the session.
 *
 * <p>Two things happen here the moment the application knows which account it is on:
 * the account's ledger currency, and the customer's own preference for that account. The
 * order matters. The stored display currency is read after the ledger currency so that it
 * wins over the per-browser choice left in local storage - the preference is the owner of
 * that figure, and local storage is only what keeps it when the network is unavailable.
 *
 * <p>The default account is adopted rather than switched to. The access token names one
 * account and is minted by the auth service, so this application cannot open a session on
 * a different one; what it can do is say so when the stored default and the session
 * disagree, instead of quietly showing one account under the heading of another.
 */
@Injectable({ providedIn: 'root' })
export class AccountStateService {
  private readonly trade = inject(TradeService);
  private readonly tokens = inject(TokenService);
  private readonly currency = inject(CurrencyService);
  private readonly preferences = inject(PreferencesService);
  private readonly theme = inject(ThemeService);

  constructor() {
    // The theme belongs to the customer's preference, not to the browser.
    this.theme.registerSaver((theme) => this.saveTheme(theme));
  }

  readonly accountCurrency = signal<string>('USD');
  readonly accountId = signal<number | null>(null);

  /** The stored preference for the signed-in account, or null if it could not be read. */
  readonly preference = signal<PreferenceResponse | null>(null);

  /** A preference read has been attempted, so null means "it failed", not "it has not started". */
  readonly preferenceLoaded = signal(false);

  /** Set when the stored default account is not the account this session may trade. */
  readonly accountNotice = signal<string | null>(null);

  /** Set when a preference was applied locally but the save was refused. */
  readonly preferenceSaveError = signal<string | null>(null);

  private preferenceAccount: number | null = null;

  /**
   * Fetch and cache the account currency from the backend.
   * Safe to call multiple times - only fetches once.
   */
  fetchAccountCurrency(): void {
    const id = this.tokens.getAccountId();
    if (!id || this.accountId() === id) {
      return; // Already fetched for this account
    }

    this.trade.getAccount(id).subscribe({
      next: (account) => {
        this.accountId.set(id);
        this.accountCurrency.set(account.currency ?? 'USD');
        this.currency.setLedger(account.currency ?? 'USD');
        this.loadPreference();
      },
      error: () => {
        // Default to USD if fetch fails
        this.accountCurrency.set('USD');
        this.currency.setLedger('USD');
        this.loadPreference();
      },
    });
  }

  /**
   * Reads the preference once per signed-in account. A failure is not an error to the
   * user: the application still has a session, it simply has no stored answers yet, and
   * the settings screen is where that becomes visible.
   */
  loadPreference(): void {
    const id = this.tokens.getAccountId();
    if (!id || this.preferenceAccount === id) {
      return;
    }

    this.preferences.get(id).subscribe({
      next: (preference) => {
        this.preferenceAccount = id;
        this.preference.set(preference);
        this.preferenceLoaded.set(true);
        this.accountNotice.set(defaultAccountNotice(id, preference.defaultAccountId));
        if (preference.displayCurrency) {
          this.currency.set(preference.displayCurrency);
        }
        this.theme.apply(preference.theme);
      },
      error: () => {
        this.preferenceAccount = id;
        this.preference.set(null);
        this.preferenceLoaded.set(true);
      },
    });
  }

  /**
   * Replaces the stored preference and keeps the session in step with it. The display
   * currency is applied locally first so the screen the customer is looking at changes
   * immediately; a refusal is reported rather than swallowed, because the two would
   * otherwise disagree with no way for the customer to tell which one the server kept.
   */
  savePreference(request: PreferenceRequest): void {
    const id = this.tokens.getAccountId();
    if (!id) {
      return;
    }

    this.preferences.put(id, request).subscribe({
      next: (preference) => {
        this.preferenceAccount = id;
        this.preference.set(preference);
        this.preferenceLoaded.set(true);
        this.preferenceSaveError.set(null);
        this.accountNotice.set(defaultAccountNotice(id, preference.defaultAccountId));
        if (preference.displayCurrency) {
          this.currency.set(preference.displayCurrency);
        }
      },
      error: (error) => {
        const message = error?.error?.message;
        this.preferenceSaveError.set(
          typeof message === 'string' && message
            ? message
            : 'Your preference could not be saved. Check the values and try again.',
        );
      },
    });
  }

  /**
   * Changes the display currency from the top-bar picker. Applied locally whether or not
   * the save succeeds, so the picker never appears broken, and the failure is surfaced.
   */
  selectDisplayCurrency(code: string): void {
    this.currency.set(code);
    const id = this.tokens.getAccountId();
    const stored = this.preference();
    // The PUT replaces the whole preference. If nothing has been read yet, saving would
    // overwrite the customer's unseen answers with defaults, so the change stays local.
    if (!id || !stored) {
      return;
    }
    this.savePreference({ ...this.requestBody(id), displayCurrency: code });
  }

  /**
   * Writes the theme the customer just chose. Without a signed-in customer (the sign-in
   * page, an operator) the change is only shown; nothing is stored anywhere.
   */
  saveTheme(theme: Theme): void {
    const id = this.tokens.getAccountId();
    if (!id) {
      return;
    }
    this.preferences.putTheme(id, theme).subscribe({
      next: (preference) => {
        const stored = this.preference();
        if (stored) {
          this.preference.set({ ...stored, theme: preference.theme ?? theme, updatedAt: preference.updatedAt });
        }
      },
      error: () => this.preferenceSaveError.set('Your theme could not be saved; it applies until you sign out.'),
    });
  }

  /**
   * Clears the "already read" mark so a failed preference read can be retried, which the
   * settings screen does with a Retry button. Nothing else calls it: the sign-in path marks
   * a failure deliberately so that a service which is down is not asked again on every
   * navigation.
   */
  retryPreference(): void {
    this.preferenceAccount = null;
    this.loadPreference();
  }

  /** The preference as a writable body, taken from the last one the service answered. */
  requestBody(accountId: number): PreferenceRequest {
    const stored = this.preference();
    return {
      defaultAccountId: stored?.defaultAccountId ?? accountId,
      alertChannel: stored?.alertChannel ?? 'NONE',
      alertContact: stored?.alertContact ?? null,
      displayCurrency: stored?.displayCurrency ?? null,
    };
  }
}

/**
 * The stored default account against the account this session may trade. Today a customer
 * has exactly one account, so this is null in practice; it is written for the day that
 * stops being true, and the answer is a sentence to the customer rather than a silent
 * substitution.
 */
function defaultAccountNotice(sessionAccount: number, stored: number | null): string | null {
  if (stored == null || stored === sessionAccount) {
    return null;
  }
  return (
    `Your saved default account is ${stored}, but this session is signed in to account ` +
    `${sessionAccount}. Balances, orders and positions shown are for account ${sessionAccount}.`
  );
}

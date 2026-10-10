import { computed, Injectable, signal } from '@angular/core';

export interface CurrencyOption {
  code: string;
  name: string;
  symbol: string;
  /** Reference units of this currency per 1 USD. Display-only. */
  perUsd: number;
}

/**
 * Presentation-only currency conversion.
 *
 * An account's ledger currency is set once, at registration, from the country the
 * applicant lives in - it is not chosen from this list and it never changes. This
 * service only changes how an amount is *displayed*, and it mirrors the same
 * reference rates as order-service and executor-service so a figure shown here
 * agrees with the figure the server used. The server remains the only thing that
 * converts a real balance.
 */
@Injectable({ providedIn: 'root' })
export class CurrencyService {
  static readonly AS_OF = 'reference rates · display only';

  static readonly OPTIONS: CurrencyOption[] = [
    { code: 'USD', name: 'US Dollar', symbol: '$', perUsd: 1 },
    { code: 'EUR', name: 'Euro', symbol: '€', perUsd: 0.92 },
    { code: 'GBP', name: 'British Pound', symbol: '£', perUsd: 0.79 },
    { code: 'INR', name: 'Indian Rupee', symbol: '₹', perUsd: 83.3 },
    { code: 'JPY', name: 'Japanese Yen', symbol: '¥', perUsd: 156.5 },
    { code: 'AUD', name: 'Australian Dollar', symbol: 'A$', perUsd: 1.5 },
    { code: 'CAD', name: 'Canadian Dollar', symbol: 'C$', perUsd: 1.36 },
    { code: 'SGD', name: 'Singapore Dollar', symbol: 'S$', perUsd: 1.34 },
    { code: 'HKD', name: 'Hong Kong Dollar', symbol: 'HK$', perUsd: 7.82 },
    { code: 'CNY', name: 'Chinese Yuan', symbol: '¥', perUsd: 7.24 },
    { code: 'KRW', name: 'South Korean Won', symbol: '₩', perUsd: 1345 },
    { code: 'AED', name: 'UAE Dirham', symbol: 'AED', perUsd: 3.67 },
    { code: 'ZAR', name: 'South African Rand', symbol: 'R', perUsd: 18.4 },
    { code: 'CHF', name: 'Swiss Franc', symbol: 'CHF', perUsd: 0.89 },
    { code: 'SEK', name: 'Swedish Krona', symbol: 'kr', perUsd: 10.5 },
    { code: 'NOK', name: 'Norwegian Krone', symbol: 'kr', perUsd: 10.7 },
    { code: 'DKK', name: 'Danish Krone', symbol: 'kr', perUsd: 6.87 },
    { code: 'BRL', name: 'Brazilian Real', symbol: 'R$', perUsd: 5.05 },
    { code: 'MXN', name: 'Mexican Peso', symbol: 'MX$', perUsd: 16.8 },
    { code: 'ILS', name: 'Israeli Shekel', symbol: '₪', perUsd: 3.72 },
    { code: 'TRY', name: 'Turkish Lira', symbol: '₺', perUsd: 32.5 },
  ];

  private readonly storagePrefix = 'akatsuki.displayCurrency.';
  readonly options = CurrencyService.OPTIONS;

  /**
   * The account's ledger currency: what balances, prices and positions are held
   * in on the server. Every amount handed to {@link display} is in this currency.
   */
  readonly ledger = signal<string>('USD');

  /** The currency amounts are shown in. Defaults to the ledger currency. */
  readonly selected = signal<string>('USD');

  readonly current = computed(
    () => this.options.find((o) => o.code === this.selected()) ?? this.options[0],
  );

  /**
   * Sets the ledger currency once the account is known, and restores the display
   * choice this browser last made for an account in that currency. A USD account
   * and an INR account therefore each start in their own currency.
   */
  setLedger(code: string): void {
    const ledger = this.options.some((o) => o.code === code) ? code : 'USD';
    this.ledger.set(ledger);
    this.selected.set(this.readStored(ledger) ?? ledger);
  }

  set(code: string): void {
    if (!this.options.some((o) => o.code === code)) return;
    this.selected.set(code);
    try {
      localStorage.setItem(this.storagePrefix + this.ledger(), code);
    } catch {
      /* storage unavailable - selection stays in memory */
    }
  }

  /** Units of `code` per one unit of the ledger currency (e.g. 1 INR = 0.012 USD). */
  ledgerRate(code: string): number {
    const ledger = this.rateFor(this.ledger());
    return ledger ? this.rateFor(code) / ledger : 0;
  }

  /** True when amounts are being shown in a currency other than the ledger's. */
  readonly converted = computed(() => this.selected() !== this.ledger());

  /** Convert a ledger-currency amount into the selected display currency. */
  convertLedger(amount: number | null | undefined): number {
    return (amount ?? 0) * this.ledgerRate(this.selected());
  }

  /** Format a ledger-currency amount in the selected display currency. */
  display(amount: number | null | undefined, fractionDigits = 2): string {
    return this.formatNative(this.selected(), this.convertLedger(amount), fractionDigits);
  }

  option(code: string): CurrencyOption {
    return this.options.find((o) => o.code === code) ?? { code, name: code, symbol: '', perUsd: 1 };
  }

  /** Reference units of the given currency per 1 USD. */
  rateFor(code: string): number {
    return this.option(code).perUsd;
  }

  /** Convert a USD amount into the given currency using the reference rate. */
  fromUsd(code: string, usd: number | null | undefined): number {
    return (usd ?? 0) * this.rateFor(code);
  }

  /** Convert an amount in the given currency into USD using the reference rate. */
  toUsd(code: string, amount: number | null | undefined): number {
    const rate = this.rateFor(code);
    return rate ? (amount ?? 0) / rate : 0;
  }

  /**
   * Convert between two currencies at the reference rates. An unknown currency on either
   * side leaves the amount as it is rather than guessing a rate for it.
   */
  convert(from: string | null | undefined, to: string, amount: number): number {
    const known = (c: string | null | undefined) => !!c && this.options.some((o) => o.code === c);
    if (!known(from) || !known(to) || from === to) {
      return amount;
    }
    return this.fromUsd(to, this.toUsd(from!, amount));
  }

  /** An amount in an instrument's own currency, shown in the display currency. */
  displayFrom(from: string | null | undefined, amount: number | null | undefined, fractionDigits = 2): string {
    if (amount == null) {
      return '—';
    }
    const known = !!from && this.options.some((o) => o.code === from);
    return known
      ? this.formatNative(this.selected(), this.convert(from, this.selected(), amount), fractionDigits)
      : amount.toLocaleString('en-US', { minimumFractionDigits: fractionDigits, maximumFractionDigits: fractionDigits });
  }

  /**
   * Format an amount already denominated in the given currency. The sign goes
   * before the symbol (-₹500.00, not ₹-500.00), and rupees use Indian digit
   * grouping (₹1,00,000.00).
   */
  formatNative(code: string, amount: number | null | undefined, fractionDigits = 2): string {
    const opt = this.option(code);
    const value = amount ?? 0;
    const digits = Math.abs(value).toLocaleString(code === 'INR' ? 'en-IN' : 'en-US', {
      minimumFractionDigits: fractionDigits,
      maximumFractionDigits: fractionDigits,
    });
    return `${value < 0 ? '-' : ''}${opt.symbol}${digits}`;
  }

  private readStored(ledger: string): string | null {
    try {
      const value = localStorage.getItem(this.storagePrefix + ledger);
      return value && CurrencyService.OPTIONS.some((o) => o.code === value) ? value : null;
    } catch {
      return null;
    }
  }
}

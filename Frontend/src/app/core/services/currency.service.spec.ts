import { TestBed } from '@angular/core/testing';
import { CurrencyService } from './currency.service';

describe('CurrencyService', () => {
  let currency: CurrencyService;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    currency = TestBed.inject(CurrencyService);
  });

  afterEach(() => localStorage.clear());

  describe('converting an instrument price', () => {
    it('converts between known currencies and leaves unknown ones alone', () => {
      expect(currency.convert('USD', 'INR', 10)).toBeCloseTo(833, 6);
      expect(currency.convert('INR', 'INR', 10)).toBe(10);
      expect(currency.convert('XYZ', 'INR', 10)).toBe(10);
      expect(currency.convert(null, 'INR', 10)).toBe(10);
    });

    it('shows a native price in the display currency', () => {
      currency.setLedger('INR');
      expect(currency.displayFrom('USD', 10)).toBe('₹833.00');
      expect(currency.displayFrom('XYZ', 1234.5)).toBe('1,234.50');
      expect(currency.displayFrom('USD', null)).toBe('—');
    });
  });

  describe('ledger and display currency', () => {
    it('shows a USD account in USD until another currency is picked', () => {
      currency.setLedger('USD');
      expect(currency.selected()).toBe('USD');
      expect(currency.converted()).toBeFalse();
      expect(currency.display(1234.5)).toBe('$1,234.50');
    });

    it('shows an INR account in INR by default', () => {
      currency.setLedger('INR');
      expect(currency.selected()).toBe('INR');
      expect(currency.display(100000)).toBe('₹1,00,000.00');
    });

    it('converts a USD ledger amount for display in INR', () => {
      currency.setLedger('USD');
      currency.set('INR');
      expect(currency.converted()).toBeTrue();
      expect(currency.convertLedger(10)).toBeCloseTo(833, 6);
    });

    it('converts an INR ledger amount for display in USD', () => {
      currency.setLedger('INR');
      currency.set('USD');
      expect(currency.convertLedger(833)).toBeCloseTo(10, 6);
    });

    it('quotes dropdown rates per one unit of the ledger currency', () => {
      currency.setLedger('INR');
      expect(currency.ledgerRate('INR')).toBe(1);
      expect(currency.ledgerRate('USD')).toBeCloseTo(1 / 83.3, 8);
      currency.setLedger('USD');
      expect(currency.ledgerRate('INR')).toBeCloseTo(83.3, 8);
    });

    it('remembers the display choice per ledger currency', () => {
      currency.setLedger('USD');
      currency.set('EUR');
      currency.setLedger('INR');
      expect(currency.selected()).toBe('INR');
      currency.setLedger('USD');
      expect(currency.selected()).toBe('EUR');
    });

    it('ignores an unknown currency code', () => {
      currency.setLedger('USD');
      currency.set('XYZ');
      expect(currency.selected()).toBe('USD');
    });

    it('falls back to USD for an unknown ledger currency', () => {
      currency.setLedger('XYZ');
      expect(currency.ledger()).toBe('USD');
    });
  });

  describe('formatNative', () => {
    it('puts the sign before the symbol', () => {
      expect(currency.formatNative('USD', -500)).toBe('-$500.00');
      expect(currency.formatNative('INR', -500)).toBe('-₹500.00');
    });

    it('uses Indian digit grouping for rupees and western grouping otherwise', () => {
      expect(currency.formatNative('INR', 12345678.9)).toBe('₹1,23,45,678.90');
      expect(currency.formatNative('USD', 12345678.9)).toBe('$12,345,678.90');
    });

    it('treats null and undefined as zero', () => {
      expect(currency.formatNative('USD', null)).toBe('$0.00');
      expect(currency.formatNative('USD', undefined)).toBe('$0.00');
    });
  });

  describe('reference conversion', () => {
    it('round-trips through USD', () => {
      const usd = currency.toUsd('INR', 8330);
      expect(usd).toBeCloseTo(100, 6);
      expect(currency.fromUsd('INR', usd)).toBeCloseTo(8330, 6);
    });
  });
});

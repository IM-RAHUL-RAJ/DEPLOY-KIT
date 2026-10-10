import { Injectable } from '@angular/core';

/**
 * The country catalogue the registration form offers.
 *
 * This mirrors auth-service/src/common/country-catalog.ts exactly. Three things are
 * derived from the selection: the ISO code sent to the server, the dial code prefixed
 * to the phone field, and the currency the new trading account will be opened in.
 *
 * The currency and dial code are shown to the applicant for confirmation but never sent:
 * the server looks both up from the country code itself, so the account cannot be opened
 * in a currency that disagrees with the country the applicant chose.
 */

export interface CountryOption {
  readonly code: string;
  readonly name: string;
  readonly dialCode: string;
  readonly currency: string;
  readonly flag: string;
}

export const COUNTRIES: readonly CountryOption[] = [
  { code: 'IN', name: 'India', dialCode: '+91', currency: 'INR', flag: '🇮🇳' },
  { code: 'US', name: 'United States', dialCode: '+1', currency: 'USD', flag: '🇺🇸' },
  { code: 'CA', name: 'Canada', dialCode: '+1', currency: 'CAD', flag: '🇨🇦' },
  { code: 'GB', name: 'United Kingdom', dialCode: '+44', currency: 'GBP', flag: '🇬🇧' },
  { code: 'IE', name: 'Ireland', dialCode: '+353', currency: 'EUR', flag: '🇮🇪' },
  { code: 'AU', name: 'Australia', dialCode: '+61', currency: 'AUD', flag: '🇦🇺' },
  { code: 'NZ', name: 'New Zealand', dialCode: '+64', currency: 'AUD', flag: '🇳🇿' },
  { code: 'JP', name: 'Japan', dialCode: '+81', currency: 'JPY', flag: '🇯🇵' },
  { code: 'SG', name: 'Singapore', dialCode: '+65', currency: 'SGD', flag: '🇸🇬' },
  { code: 'HK', name: 'Hong Kong', dialCode: '+852', currency: 'HKD', flag: '🇭🇰' },
  { code: 'CN', name: 'China', dialCode: '+86', currency: 'CNY', flag: '🇨🇳' },
  { code: 'KR', name: 'South Korea', dialCode: '+82', currency: 'KRW', flag: '🇰🇷' },
  { code: 'AE', name: 'United Arab Emirates', dialCode: '+971', currency: 'AED', flag: '🇦🇪' },
  { code: 'ZA', name: 'South Africa', dialCode: '+27', currency: 'ZAR', flag: '🇿🇦' },
  { code: 'DE', name: 'Germany', dialCode: '+49', currency: 'EUR', flag: '🇩🇪' },
  { code: 'FR', name: 'France', dialCode: '+33', currency: 'EUR', flag: '🇫🇷' },
  { code: 'ES', name: 'Spain', dialCode: '+34', currency: 'EUR', flag: '🇪🇸' },
  { code: 'IT', name: 'Italy', dialCode: '+39', currency: 'EUR', flag: '🇮🇹' },
  { code: 'PT', name: 'Portugal', dialCode: '+351', currency: 'EUR', flag: '🇵🇹' },
  { code: 'NL', name: 'Netherlands', dialCode: '+31', currency: 'EUR', flag: '🇳🇱' },
  { code: 'BE', name: 'Belgium', dialCode: '+32', currency: 'EUR', flag: '🇧🇪' },
  { code: 'CH', name: 'Switzerland', dialCode: '+41', currency: 'CHF', flag: '🇨🇭' },
  { code: 'AT', name: 'Austria', dialCode: '+43', currency: 'EUR', flag: '🇦🇹' },
  { code: 'SE', name: 'Sweden', dialCode: '+46', currency: 'SEK', flag: '🇸🇪' },
  { code: 'NO', name: 'Norway', dialCode: '+47', currency: 'NOK', flag: '🇳🇴' },
  { code: 'DK', name: 'Denmark', dialCode: '+45', currency: 'DKK', flag: '🇩🇰' },
  { code: 'FI', name: 'Finland', dialCode: '+358', currency: 'EUR', flag: '🇫🇮' },
  { code: 'BR', name: 'Brazil', dialCode: '+55', currency: 'BRL', flag: '🇧🇷' },
  { code: 'MX', name: 'Mexico', dialCode: '+52', currency: 'MXN', flag: '🇲🇽' },
  { code: 'IL', name: 'Israel', dialCode: '+972', currency: 'ILS', flag: '🇮🇱' },
  { code: 'TR', name: 'Türkiye', dialCode: '+90', currency: 'TRY', flag: '🇹🇷' },
];

export const RISK_APPETITES = [
  {
    value: 'CONSERVATIVE' as const,
    label: 'conservative',
    blurb: 'Capital preservation first. Small, infrequent positions with tight limits.',
  },
  {
    value: 'BALANCED' as const,
    label: 'balanced',
    blurb: 'A measured mix of growth and stability. The middle road most traders take.',
  },
  {
    value: 'AGGRESSIVE' as const,
    label: 'aggressive',
    blurb: 'Maximum conviction. Larger positions accept larger moves against them.',
  },
];

@Injectable({ providedIn: 'root' })
export class CountryCatalogService {
  readonly countries = COUNTRIES;

  /** Alphabetical by display name, which is how an applicant scans for their own. */
  readonly sortedCountries: readonly CountryOption[] = [...COUNTRIES].sort((a, b) =>
    a.name.localeCompare(b.name)
  );

  find(code: string | null | undefined): CountryOption | undefined {
    if (!code) {
      return undefined;
    }
    return COUNTRIES.find((country) => country.code === code.trim().toUpperCase());
  }
}
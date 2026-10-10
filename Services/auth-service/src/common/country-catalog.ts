/**
 * Country catalogue shared by the registration DTO validation and the UI.
 *
 * Registration derives three things from the selected country: the ISO code
 * stored on the user, the dial code prefixed to the phone field, and the currency
 * the new trading account is opened in. The client sends the country code; the
 * server never trusts the currency or the dial code it is given, it looks them up
 * here, so a caller cannot open an INR account while claiming to be in the US.
 */

export interface CountryDefinition {
  /** ISO 3166-1 alpha-2 code. */
  readonly code: string;
  readonly name: string;
  /** Primary E.164 dial code, e.g. '+91'. */
  readonly dialCode: string;
  /** Every dial code accepted for this country (Canada and the US share '+1'). */
  readonly dialCodes: readonly string[];
  readonly currency: string;
  readonly flag: string;
}

export const COUNTRIES: readonly CountryDefinition[] = [
  { code: 'IN', name: 'India', dialCode: '+91', dialCodes: ['+91'], currency: 'INR', flag: '🇮🇳' },
  { code: 'US', name: 'United States', dialCode: '+1', dialCodes: ['+1'], currency: 'USD', flag: '🇺🇸' },
  { code: 'CA', name: 'Canada', dialCode: '+1', dialCodes: ['+1'], currency: 'CAD', flag: '🇨🇦' },
  { code: 'GB', name: 'United Kingdom', dialCode: '+44', dialCodes: ['+44'], currency: 'GBP', flag: '🇬🇧' },
  { code: 'IE', name: 'Ireland', dialCode: '+353', dialCodes: ['+353'], currency: 'EUR', flag: '🇮🇪' },
  { code: 'AU', name: 'Australia', dialCode: '+61', dialCodes: ['+61'], currency: 'AUD', flag: '🇦🇺' },
  { code: 'NZ', name: 'New Zealand', dialCode: '+64', dialCodes: ['+64'], currency: 'AUD', flag: '🇳🇿' },
  { code: 'JP', name: 'Japan', dialCode: '+81', dialCodes: ['+81'], currency: 'JPY', flag: '🇯🇵' },
  { code: 'SG', name: 'Singapore', dialCode: '+65', dialCodes: ['+65'], currency: 'SGD', flag: '🇸🇬' },
  { code: 'HK', name: 'Hong Kong', dialCode: '+852', dialCodes: ['+852'], currency: 'HKD', flag: '🇭🇰' },
  { code: 'CN', name: 'China', dialCode: '+86', dialCodes: ['+86'], currency: 'CNY', flag: '🇨🇳' },
  { code: 'KR', name: 'South Korea', dialCode: '+82', dialCodes: ['+82'], currency: 'KRW', flag: '🇰🇷' },
  { code: 'AE', name: 'United Arab Emirates', dialCode: '+971', dialCodes: ['+971'], currency: 'AED', flag: '🇦🇪' },
  { code: 'ZA', name: 'South Africa', dialCode: '+27', dialCodes: ['+27'], currency: 'ZAR', flag: '🇿🇦' },
  { code: 'DE', name: 'Germany', dialCode: '+49', dialCodes: ['+49'], currency: 'EUR', flag: '🇩🇪' },
  { code: 'FR', name: 'France', dialCode: '+33', dialCodes: ['+33'], currency: 'EUR', flag: '🇫🇷' },
  { code: 'ES', name: 'Spain', dialCode: '+34', dialCodes: ['+34'], currency: 'EUR', flag: '🇪🇸' },
  { code: 'IT', name: 'Italy', dialCode: '+39', dialCodes: ['+39'], currency: 'EUR', flag: '🇮🇹' },
  { code: 'PT', name: 'Portugal', dialCode: '+351', dialCodes: ['+351'], currency: 'EUR', flag: '🇵🇹' },
  { code: 'NL', name: 'Netherlands', dialCode: '+31', dialCodes: ['+31'], currency: 'EUR', flag: '🇳🇱' },
  { code: 'BE', name: 'Belgium', dialCode: '+32', dialCodes: ['+32'], currency: 'EUR', flag: '🇧🇪' },
  { code: 'CH', name: 'Switzerland', dialCode: '+41', dialCodes: ['+41'], currency: 'CHF', flag: '🇨🇭' },
  { code: 'AT', name: 'Austria', dialCode: '+43', dialCodes: ['+43'], currency: 'EUR', flag: '🇦🇹' },
  { code: 'SE', name: 'Sweden', dialCode: '+46', dialCodes: ['+46'], currency: 'SEK', flag: '🇸🇪' },
  { code: 'NO', name: 'Norway', dialCode: '+47', dialCodes: ['+47'], currency: 'NOK', flag: '🇳🇴' },
  { code: 'DK', name: 'Denmark', dialCode: '+45', dialCodes: ['+45'], currency: 'DKK', flag: '🇩🇰' },
  { code: 'FI', name: 'Finland', dialCode: '+358', dialCodes: ['+358'], currency: 'EUR', flag: '🇫🇮' },
  { code: 'BR', name: 'Brazil', dialCode: '+55', dialCodes: ['+55'], currency: 'BRL', flag: '🇧🇷' },
  { code: 'MX', name: 'Mexico', dialCode: '+52', dialCodes: ['+52'], currency: 'MXN', flag: '🇲🇽' },
  { code: 'IL', name: 'Israel', dialCode: '+972', dialCodes: ['+972'], currency: 'ILS', flag: '🇮🇱' },
  { code: 'TR', name: 'Türkiye', dialCode: '+90', dialCodes: ['+90'], currency: 'TRY', flag: '🇹🇷' },
];

const BY_CODE = new Map<string, CountryDefinition>(
  COUNTRIES.map((country) => [country.code, country]),
);

/** Case-insensitive lookup; returns undefined for an unknown code. */
export function findCountry(code: string | undefined | null): CountryDefinition | undefined {
  if (!code) {
    return undefined;
  }
  return BY_CODE.get(code.trim().toUpperCase());
}

/**
 * Currency the account should be opened in for the given country, or undefined
 * when the country is not in the catalogue.
 */
export function currencyForCountry(code: string | undefined | null): string | undefined {
  return findCountry(code)?.currency;
}

/** True when the supplied dial code is one this country actually uses. */
export function isDialCodeValidFor(countryCode: string, dialCode: string): boolean {
  const country = findCountry(countryCode);
  if (!country) {
    return false;
  }
  const normalised = dialCode.trim().startsWith('+') ? dialCode.trim() : `+${dialCode.trim()}`;
  return country.dialCodes.includes(normalised);
}
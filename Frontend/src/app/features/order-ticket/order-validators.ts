import { AbstractControl, ValidationErrors, Validators } from '@angular/forms';

/**
 * Client-side order validation. It keeps obvious mistakes off the wire; it is not
 * enforcement. Business rules 1 to 8 are decided by the Trade REST API, and the
 * error catalogue renders whatever it refuses.
 */

/** The symbol shape trade-api.yaml allows: 1 to 20 characters. */
export const SYMBOL_PATTERN = /^[A-Z0-9.:-]{1,20}$/i;

/** A whole number greater than zero. */
export const QUANTITY_PATTERN = /^[1-9]\d*$/;

/** Greater than zero with at most two decimal places: the shape the ledger stores. */
export function priceValidator(control: AbstractControl): ValidationErrors | null {
  if (control.value === null || control.value === undefined || control.value === '') return null;
  const raw = String(control.value);
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) return { priceInvalid: true };
  if (parseFloat(raw) <= 0) return { priceInvalid: true };
  return null;
}

export const symbolValidators = [
  Validators.required,
  Validators.maxLength(20),
  Validators.pattern(SYMBOL_PATTERN),
];

export const quantityValidators = [
  Validators.required,
  Validators.min(1),
  Validators.pattern(QUANTITY_PATTERN),
];

import { FormControl } from '@angular/forms';
import { priceValidator, quantityValidators, symbolValidators } from './order-validators';

describe('order ticket validation', () => {
  const quantity = (value: unknown) => new FormControl(value, quantityValidators).valid;
  const symbol = (value: unknown) => new FormControl(value, symbolValidators).valid;
  const price = (value: unknown) => new FormControl(value, priceValidator).valid;

  it('accepts a whole quantity greater than zero', () => {
    expect(quantity(1)).toBeTrue();
    expect(quantity(250)).toBeTrue();
  });

  it('rejects a zero, negative, fractional or missing quantity', () => {
    expect(quantity(0)).toBeFalse();
    expect(quantity(-5)).toBeFalse();
    expect(quantity(1.5)).toBeFalse();
    expect(quantity(null)).toBeFalse();
  });

  it('accepts a price greater than zero with at most two decimals', () => {
    expect(price('25.5')).toBeTrue();
    expect(price('0.01')).toBeTrue();
    expect(price(100)).toBeTrue();
  });

  it('rejects a price of zero, below zero or with three decimals', () => {
    expect(price('0')).toBeFalse();
    expect(price('-1')).toBeFalse();
    expect(price('25.999')).toBeFalse();
  });

  it('accepts the symbol shapes the contract allows and rejects others', () => {
    expect(symbol('AAPL')).toBeTrue();
    expect(symbol('INFY.NS')).toBeTrue();
    expect(symbol('')).toBeFalse();
    expect(symbol('!!!')).toBeFalse();
    expect(symbol('X'.repeat(21))).toBeFalse();
  });
});

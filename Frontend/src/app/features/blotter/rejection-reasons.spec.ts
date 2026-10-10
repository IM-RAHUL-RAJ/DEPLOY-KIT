import { KNOWN_REJECTION_CODES, explainRejection } from './rejection-reasons';

describe('explainRejection', () => {
  it('explains the funds rejection the executor now owns', () => {
    const e = explainRejection('INSUFFICIENT_FUNDS');
    expect(e.code).toBe('INSUFFICIENT_FUNDS');
    expect(e.label).toContain('cash');
    expect(e.nextStep).toBeTruthy();
  });

  it('explains the holdings rejection the executor now owns', () => {
    expect(explainRejection('INSUFFICIENT_HOLDINGS').label).toContain('holding');
  });

  it('matches on the leading code when the executor appends detail', () => {
    expect(explainRejection('PRICE_NOT_AVAILABLE - feed down').code).toBe('PRICE_NOT_AVAILABLE');
    expect(explainRejection('MARKET_CLOSED end of day').code).toBe('MARKET_CLOSED');
  });

  it('is case-insensitive about the code', () => {
    expect(explainRejection('insufficient_funds').code).toBe('INSUFFICIENT_FUNDS');
  });

  it('falls back to a readable sentence for a code it has never seen', () => {
    const e = explainRejection('SOMETHING_NEW');
    expect(e.code).toBe('SOMETHING_NEW');
    expect(e.label).toBe('Rejected by the executor');
    expect(e.detail.length).toBeGreaterThan(0);
  });

  it('never returns a blank explanation for a missing reason', () => {
    for (const reason of [null, undefined, '', '   ']) {
      const e = explainRejection(reason);
      expect(e.code).toBe('UNKNOWN');
      expect(e.label).toBeTruthy();
    }
  });

  it('covers every rejection reason the executor can write', () => {
    // FillRuleEvaluator and EndOfDayTask in executor-service.
    const executorReasons = [
      'ACCOUNT_NOT_ACTIVE',
      'INSUFFICIENT_FUNDS',
      'INSUFFICIENT_HOLDINGS',
      'PRICE_NOT_AVAILABLE',
      'INVALID_SIDE',
      'MARKET_CLOSED',
    ];
    for (const code of executorReasons) {
      expect(KNOWN_REJECTION_CODES).withContext(code).toContain(code);
    }
  });

  it('gives every known code a label, a detail and a next step', () => {
    for (const code of KNOWN_REJECTION_CODES) {
      const e = explainRejection(code);
      expect(e.label).withContext(code).toBeTruthy();
      expect(e.detail).withContext(code).toBeTruthy();
      expect(e.nextStep).withContext(code).toBeTruthy();
    }
  });
});

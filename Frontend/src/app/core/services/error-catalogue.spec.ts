import {
  ERROR_CATALOGUE,
  NETWORK_ERROR,
  UNKNOWN_ERROR,
  resolveErrorMessage,
} from './error-catalogue';
import contractCodes from './contract-error-codes.json';

describe('error catalogue', () => {
  // Read out of the errorCode enums of trade-api.yaml and auth-api.yaml by
  // scripts/extract-error-codes.mjs, which runs before every `npm test`. A code
  // added to either contract without a message fails here.
  const contracts = Object.entries(contractCodes) as [string, string[]][];

  for (const [contract, codes] of contracts) {
    it(`maps every errorCode in ${contract} to a readable sentence`, () => {
      expect(codes.length).withContext(`${contract} lists no error codes`).toBeGreaterThan(0);
      for (const code of codes) {
        expect(ERROR_CATALOGUE[code]).withContext(`missing message for ${code}`).toBeTruthy();
      }
    });
  }

  it('covers the eight codes the brief names', () => {
    for (const code of ['ACC-404', 'ACC-403', 'INS-404', 'ORD-400', 'ORD-409', 'VAL-422', 'AUTH-401', 'AUTH-409']) {
      expect(ERROR_CATALOGUE[code]).withContext(`missing message for ${code}`).toBeTruthy();
    }
  });

  it('resolves a known code to its trader-facing message', () => {
    expect(resolveErrorMessage('ORD-400', 400)).toBe(ERROR_CATALOGUE['ORD-400']);
  });

  it('falls back to a readable sentence for an unrecognised code', () => {
    expect(resolveErrorMessage('ZZZ-999', 418)).toBe(UNKNOWN_ERROR);
  });

  it('renders a readable sentence for a request that never reached a service', () => {
    expect(resolveErrorMessage(undefined, 0)).toBe(NETWORK_ERROR);
  });
});

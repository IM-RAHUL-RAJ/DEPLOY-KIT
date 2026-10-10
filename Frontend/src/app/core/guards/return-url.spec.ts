import { isSafeReturnUrl, safeReturnUrl } from './return-url';

describe('return address', () => {
  it('accepts a path on this origin', () => {
    expect(isSafeReturnUrl('/blotter')).toBeTrue();
    expect(isSafeReturnUrl('/admin/approvals?tab=pending')).toBeTrue();
  });

  it('refuses an absolute URL to another host', () => {
    expect(isSafeReturnUrl('https://evil.example.com')).toBeFalse();
  });

  it('refuses protocol-relative forms that browsers send to another host', () => {
    expect(isSafeReturnUrl('//evil.example.com')).toBeFalse();
    expect(isSafeReturnUrl('/\\evil.example.com')).toBeFalse();
  });

  it('falls back when the address is missing or unsafe', () => {
    expect(safeReturnUrl(null)).toBe('/dashboard');
    expect(safeReturnUrl('//evil.example.com', '/')).toBe('/');
  });
});

/**
 * A return address is followed only when it is a path on this origin.
 *
 * '/blotter' is a path. '//evil.example' and '/\evil.example' also start with a
 * slash, but browsers read both as a protocol-relative URL to another host, so
 * they are refused along with anything absolute. Following an unchecked return
 * address from the login page is an open redirect.
 */
export function isSafeReturnUrl(url: string | null | undefined): url is string {
  return (
    typeof url === 'string' &&
    url.startsWith('/') &&
    !url.startsWith('//') &&
    !url.startsWith('/\\')
  );
}

export function safeReturnUrl(url: string | null | undefined, fallback = '/dashboard'): string {
  return isSafeReturnUrl(url) ? url : fallback;
}

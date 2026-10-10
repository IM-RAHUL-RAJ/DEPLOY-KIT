/**
 * Complete error code catalogue for both API contracts.
 * Branch on errorCode ONLY - never on message (messages are for developer logs and
 * change without notice). Every known code maps to a trader-facing sentence, and an
 * unmapped code falls back to the default.
 */
export const ERROR_CATALOGUE: Record<string, string> = {
  // Trade REST API codes
  'ACC-404': 'Your account could not be found. Please contact support.',
  'ACC-403':
    'This account is not active or you are not authorised to access it. If you have just registered, your account may be awaiting admin approval.',
  'INS-404':
    'This instrument cannot be traded. It may be delisted or not available on this platform.',
  'ORD-400': 'You do not have enough cash to place this order. Reduce the quantity or price and try again.',
  'ORD-404': 'That order could not be found. It may belong to another account or never have been placed.',
  'ORD-409':
    'You do not have enough of this holding to sell, or this order has already been placed.',
  'ORD-422': 'The order could not be priced as submitted. Check the price and quantity and try again.',
  'MKT-503':
    'No live market price is available for that instrument right now. Try again in a moment.',
  'FX-422':
    'Your account currency has no exchange rate configured on this service, so the order cannot be priced. Please contact support.',

  // Auth service codes
  'AUTH-401': 'Your session has expired or the sign-in was refused. Please sign in again.',
  'AUTH-403':
    'Your account is not active yet. A newly registered account must be approved by an administrator before it can sign in.',
  'AUTH-404': 'We could not find a pending registration for that email address.',
  'AUTH-409': 'That username or email is already taken. Please choose different details.',
  'AUTH-410': 'That verification code has expired. Please start the registration again.',
  'AUTH-422': 'The current password you entered is incorrect. Please try again.',
  'RATE-429': 'Too many attempts in a short time. Please wait a few minutes and try again.',
  'USR-404': 'That account could not be found. It may already have been removed.',

  // Both contracts
  'VAL-422': 'One or more fields are not acceptable. Please check the values you entered.',

  // Returned when the service could not read the request. Almost always a
  // truncated payload or a stale client, not something the user typed wrong.
  'API-400': 'That request could not be read by the service. Please refresh and try again.',

  // Returned when a path does not exist. Usually a stale link or a service
  // restart mid-request rather than anything the user did.
  'API-404': 'That request went somewhere this service does not serve. Please refresh and try again.',

  // Either service failed on its side. Nothing the trader entered caused it.
  'SRV-500':
    'The service could not complete that request. Please try again in a moment, and contact support if it keeps happening.',
};

export const NETWORK_ERROR =
  'The service could not be reached. Check that all services are running and that your network allows this origin.';

export const UNKNOWN_ERROR =
  'An unexpected error occurred. Please try again or contact support if the problem persists.';

export function resolveErrorMessage(errorCode?: string, status?: number): string {
  if (status === 0) return NETWORK_ERROR;
  if (errorCode && ERROR_CATALOGUE[errorCode]) return ERROR_CATALOGUE[errorCode];
  return UNKNOWN_ERROR;
}
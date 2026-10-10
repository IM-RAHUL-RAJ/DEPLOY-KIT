/**
 * Every address and credential the journeys use, read from the environment so all
 * specs run against one set of services. Credentials have no default: a journey
 * that silently signed in with a value baked into the source would pass on one
 * machine and test nothing on the next. See .env.e2e.example.
 */
function required(name: string): string {
  const value = process.env[name];
  if (!value) {
    throw new Error(`${name} is not set. Export it (see e2e/.env.e2e.example) before running Playwright.`);
  }
  return value;
}

function optional(name: string, fallback: string): string {
  return process.env[name] || fallback;
}

export const env = {
  baseUrl: optional('E2E_BASE_URL', 'http://localhost:4200'),
  tradeApi: optional('E2E_TRADE_API', 'http://localhost:8081'),
  authApi: optional('E2E_AUTH_API', 'http://localhost:3000'),
  get username(): string {
    return required('E2E_USERNAME');
  },
  get password(): string {
    return required('E2E_PASSWORD');
  },
  get accountId(): string {
    return required('E2E_ACCOUNT_ID');
  },
  symbol: optional('E2E_SYMBOL', 'AAPL'),
  get adminUsername(): string {
    return required('E2E_ADMIN_USERNAME');
  },
  get adminPassword(): string {
    return required('E2E_ADMIN_PASSWORD');
  },
};

import { test, expect } from '@playwright/test';
import { env } from './env';
import { readFile } from 'node:fs/promises';
import { resolve } from 'node:path';

/**
 * E2E Journey: registration, approval, suspension and reinstatement.
 *
 * The tests are serial and share one applicant, because the lifecycle only reads
 * correctly along its sequence: pending -> active -> suspended -> active. If the
 * registration step fails the later steps are skipped rather than reporting
 * misleading failures against a user that was never created.
 *
 * Addresses and credentials come from the environment:
 * E2E_BASE_URL, E2E_AUTH_API, E2E_TRADE_API,
 * E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD, E2E_AUTH_LOG.
 *
 * SMTP is unset in this environment, so the six-digit code is read from the auth
 * service log. That is the behaviour under test, not a workaround.
 */

const BASE_URL = env.baseUrl;
const AUTH_API = env.authApi;
const TRADE_API = env.tradeApi;
// Resolved from this file so the suite does not depend on a machine-specific path.
const AUTH_LOG =
  process.env['E2E_AUTH_LOG'] ?? resolve(__dirname, '../../Services/auth-service/auth.log');

const stamp = Date.now();
const APPLICANT = {
  firstName: 'E2e',
  lastName: 'Applicant',
  username: `e2e.applicant.${stamp}`,
  email: `e2e.applicant.${stamp}@example.com`,
  password: 'Capstone@2026',
  confirmPassword: 'Capstone@2026',
  countryCode: 'IN',
  phoneCountryCode: '+91',
  phoneNumber: `9${String(stamp).slice(-9)}`,
  riskAppetite: 'BALANCED',
};

/** Mirrors MailService.maskEmail: first character, then stars, then the domain. */
function maskEmail(email: string): string {
  const [local, domain] = email.split('@');
  if (!local || !domain) return '***';
  return `${local.slice(0, 1)}${'*'.repeat(Math.max(1, local.length - 1))}@${domain}`;
}

/** Reads the six-digit code the auth service logged, because SMTP is unset. */
async function readLoggedCode(email: string): Promise<string> {
  const log = await readFile(AUTH_LOG, 'utf8');
  const line = log
    .split('\n')
    .reverse()
    .find((l) => l.includes(maskEmail(email)) && /\b\d{6}\b/.test(l));
  if (!line) {
    throw new Error(`No OTP found for ${maskEmail(email)} in ${AUTH_LOG}`);
  }
  return line.match(/\b(\d{6})\b/)![1];
}

async function adminToken(page: import('@playwright/test').Page): Promise<string> {
  const res = await page.request.post(`${AUTH_API}/auth/login`, {
    data: { identifier: env.adminUsername, password: env.adminPassword },
  });
  expect(res.ok(), 'admin login should succeed').toBeTruthy();
  const body = (await res.json()) as { accessToken: string };
  return body.accessToken;
}

test.describe.configure({ mode: 'serial' });

test.describe('Onboarding journey', () => {
  test('register, wait for approval, then sign in', async ({ page }) => {
    // 1. Complete the profile step in the browser.
    await page.goto(`${BASE_URL}/login`);
    await page.evaluate(() => localStorage.clear());
    await page.goto(`${BASE_URL}/register`);

    await page.getByTestId('firstName-input').fill(APPLICANT.firstName);
    await page.getByTestId('lastName-input').fill(APPLICANT.lastName);
    await page.getByTestId('register-username-input').fill(APPLICANT.username);
    await page.getByTestId('email-input').fill(APPLICANT.email);
    await page.getByTestId('register-password-input').fill(APPLICANT.password);
    await page.getByTestId('confirmPassword-input').fill(APPLICANT.confirmPassword);
    await page.getByTestId('country-select').selectOption(APPLICANT.countryCode);
    await page.getByTestId('phone-number-input').fill(APPLICANT.phoneNumber);
    await page.locator('[data-testid="risk-balanced"]').click();
    await page.getByTestId('register-submit').click();

    // 2. The step moved to code entry and nothing was signed in yet.
    await expect(page.getByTestId('otp-input')).toBeVisible({ timeout: 15000 });
    expect(await page.evaluate(() => localStorage.getItem('access_token'))).toBeNull();

    // 3. The code comes from the auth log because SMTP is unset.
    const code = await readLoggedCode(APPLICANT.email);
    await page.getByTestId('otp-input').fill(code);
    await page.getByTestId('verify-submit').click();
    await expect(page.getByTestId('go-login')).toBeVisible({ timeout: 15000 });

    // 4. Before approval, signing in is refused with a status-specific message.
    await page.goto(`${BASE_URL}/login`);
    await page.evaluate(() => localStorage.clear());
    await page.getByTestId('username-input').fill(APPLICANT.username);
    await page.getByTestId('password-input').fill(APPLICANT.password);
    await page.getByTestId('login-submit').click();
    const banner = page.locator('.error-banner');
    await expect(banner).toBeVisible({ timeout: 10000 });
    await expect(banner).toContainText(/must be approved|not active/i);

    // 5. An administrator approves the applicant.
    const token = await adminToken(page);
    const pending = await page.request.get(`${TRADE_API}/api/v1/admin/users/pending`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(pending.ok()).toBeTruthy();
    const queue = (await pending.json()) as { userId: number; username: string }[];
    const found = queue.find((u) => u.username === APPLICANT.username);
    expect(found, 'the applicant should appear in the pending queue').toBeTruthy();

    const approve = await page.request.post(`${TRADE_API}/api/v1/admin/users/${found!.userId}/approve`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { reason: 'E2E approval' },
    });
    expect(approve.ok(), `approve should succeed: ${approve.status()}`).toBeTruthy();
    const approved = (await approve.json()) as { status: string; accountStatus: string };
    expect(approved.status).toBe('ACTIVE');
    expect(approved.accountStatus).toBe('ACTIVE');

    // 6. Now the applicant can sign in and reach the dashboard.
    await page.goto(`${BASE_URL}/login`);
    await page.evaluate(() => localStorage.clear());
    await page.getByTestId('username-input').fill(APPLICANT.username);
    await page.getByTestId('password-input').fill(APPLICANT.password);
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/\/dashboard/, { timeout: 10000 });
  });

  test('an administrator sees the applicant in the approval console', async ({ page }) => {
    const token = await adminToken(page);
    const users = await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    expect(users.ok()).toBeTruthy();
    const all = (await users.json()) as {
      username: string; status: string; accountStatus: string; cashBalance: number | null;
    }[];
    const found = all.find((u) => u.username === APPLICANT.username);
    expect(found, 'the approved applicant should be listed').toBeTruthy();
    expect(found!.status).toBe('ACTIVE');
    // The user and the account must move together, otherwise the two views of
    // "can this person trade" disagree.
    expect(found!.accountStatus).toBe('ACTIVE');
    // Approval is what pays the opening balance.
    expect(Number(found!.cashBalance)).toBeGreaterThan(0);
  });

  test('suspension blocks trading and reinstatement restores it', async ({ page }) => {
    const token = await adminToken(page);
    const users = await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const all = (await users.json()) as { userId: number; username: string; status: string }[];
    const target = all.find((u) => u.username === APPLICANT.username);
    expect(target, 'the applicant from the registration step').toBeTruthy();

    const suspend = await page.request.post(`${TRADE_API}/api/v1/admin/users/${target!.userId}/suspend`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { reason: 'E2E suspension' },
    });
    expect(suspend.ok()).toBeTruthy();
    expect(((await suspend.json()) as { status: string }).status).toBe('SUSPENDED');

    // A suspended user cannot sign in at all, so no order can reach the wire.
    const login = await page.request.post(`${AUTH_API}/auth/login`, {
      data: { identifier: APPLICANT.username, password: APPLICANT.password },
    });
    expect(login.status(), 'a suspended user must be refused at login').toBe(403);

    // Reinstatement must not credit another opening balance.
    const before = (await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.json())) as { userId: number; cashBalance: number | null }[];
    const reinstate = await page.request.post(`${TRADE_API}/api/v1/admin/users/${target!.userId}/reinstate`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { reason: 'E2E reinstatement' },
    });
    expect(reinstate.ok()).toBeTruthy();
    const after = (await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.json())) as { userId: number; status: string; cashBalance: number | null }[];

    const b = before.find((u) => u.userId === target!.userId)!;
    const a = after.find((u) => u.userId === target!.userId)!;
    expect(a.status).toBe('ACTIVE');
    expect(a.cashBalance, 'reinstatement must not pay the user again').toBe(b.cashBalance);
  });

  test('an active user cannot be rejected, only suspended', async ({ page }) => {
    const token = await adminToken(page);
    const users = await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const all = (await users.json()) as { userId: number; username: string; status: string }[];
    const target = all.find((u) => u.username === APPLICANT.username);
    expect(target?.status).toBe('ACTIVE');

    const reject = await page.request.post(`${TRADE_API}/api/v1/admin/users/${target!.userId}/reject`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { reason: 'should not be allowed' },
    });
    expect(reject.status(), 'rejecting a live user would strand their orders').toBe(422);
  });

  test('reinstating a user who is not suspended is refused', async ({ page }) => {
    const token = await adminToken(page);
    const users = await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const all = (await users.json()) as { userId: number; username: string; status: string }[];
    const target = all.find((u) => u.username === APPLICANT.username);
    expect(target?.status).toBe('ACTIVE');

    const reinstate = await page.request.post(`${TRADE_API}/api/v1/admin/users/${target!.userId}/reinstate`, {
      headers: { Authorization: `Bearer ${token}` },
      data: { reason: 'not suspended' },
    });
    expect(reinstate.status()).toBe(422);
  });
});
import { test, expect } from '@playwright/test';
import { env } from './env';

/**
 * E2E Journey: Sign in, against the real Auth service.
 * Covers:
 * - Route guard redirect (visiting a guarded route unauthenticated)
 * - Refused sign-in (wrong credentials)
 * - Successful sign-in
 * - Arriving at the return address after authentication
 * - Signing out (route-guards.spec.ts covers what a signed-out visitor can reach)
 *
 * Every test starts from a fresh browser context with nothing in storage, and
 * leaves nothing behind that another journey needs.
 */

test.describe('Sign in journey', () => {
  test('route guard redirects an unauthenticated visitor to login carrying the return address', async ({ page }) => {
    await page.goto(`${env.baseUrl}/dashboard`);
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fdashboard/);
    await expect(page.getByTestId('login-submit')).toBeVisible();
  });

  test('refused sign-in renders a readable error message', async ({ page }) => {
    await page.goto(`${env.baseUrl}/login`);

    const response = page.waitForResponse(`${env.authApi}/auth/login`);
    await page.getByTestId('username-input').fill(`nobody-${Date.now()}`);
    await page.getByTestId('password-input').fill('not-the-password');
    await page.getByTestId('login-submit').click();
    expect((await response).status()).toBe(401);

    const error = page.locator('.error-banner');
    await expect(error).toBeVisible();
    await expect(error).toContainText(/sign/i);
  });

  test('successful sign-in against the auth service lands on the dashboard', async ({ page }) => {
    await page.goto(`${env.baseUrl}/login`);

    const response = page.waitForResponse(`${env.authApi}/auth/login`);
    await page.getByTestId('username-input').fill(env.username);
    await page.getByTestId('password-input').fill(env.password);
    await page.getByTestId('login-submit').click();
    expect((await response).status()).toBe(200);

    await expect(page).toHaveURL(/\/dashboard/);
  });

  test('sign-in with a return address lands where the user was going', async ({ page }) => {
    await page.goto(`${env.baseUrl}/blotter`);
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fblotter/);

    await page.getByTestId('username-input').fill(env.username);
    await page.getByTestId('password-input').fill(env.password);
    await page.getByTestId('login-submit').click();

    await expect(page).toHaveURL(/\/blotter$/);
  });


  test('signing out returns to sign-in and forgets the session', async ({ page }) => {
    await page.goto(`${env.baseUrl}/login`);
    await page.getByTestId('username-input').fill(env.username);
    await page.getByTestId('password-input').fill(env.password);
    await page.getByTestId('login-submit').click();
    await expect(page).toHaveURL(/\/dashboard/);

    await page.getByRole('button', { name: /sign out/i }).click();
    await expect(page).toHaveURL(/\/login/);
    expect(await page.evaluate(() => localStorage.getItem('lb_access_token'))).toBeNull();
  });
});

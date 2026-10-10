import { test, expect } from '@playwright/test';
import { env } from './env';
import { signInAsTrader } from './support';

/**
 * E2E journey: route guards.
 *
 * The guard is a usability control, not a security one: the bundle is public and
 * the Trade REST API authorises every call. What it must do is send a signed-out
 * visitor to sign-in carrying where they were going, keep traders out of the
 * admin area, and never follow a return address off this origin.
 */

const TRADER_ROUTES = ['/dashboard', '/order', '/blotter', '/holdings', '/analysis', '/watchlist'];
const ADMIN_ROUTES = ['/admin/overview', '/admin/accounts', '/admin/approvals', '/admin/users', '/admin/orders'];

test.describe('Route guards', () => {
  test('a signed-out visitor to any trader page is sent to sign-in carrying the address', async ({ page }) => {
    for (const route of TRADER_ROUTES) {
      await page.goto(`${env.baseUrl}${route}`);
      await expect(page, route).toHaveURL(new RegExp(`/login\\?returnUrl=${encodeURIComponent(route)}$`));
      await expect(page.getByTestId('login-submit')).toBeVisible();
    }
  });

  test('a signed-out visitor to any admin page is sent to sign-in carrying the address', async ({ page }) => {
    for (const route of ADMIN_ROUTES) {
      await page.goto(`${env.baseUrl}${route}`);
      await expect(page, route).toHaveURL(new RegExp(`/login\\?returnUrl=${encodeURIComponent(route)}$`));
    }
  });

  test('sign-in and registration are the only pages open without a session', async ({ page }) => {
    await page.goto(`${env.baseUrl}/login`);
    await expect(page).toHaveURL(/\/login$/);
    await expect(page.getByTestId('username-input')).toBeVisible();
    await page.goto(`${env.baseUrl}/register`);
    await expect(page).toHaveURL(/\/register$/);
  });

  test('an unknown address falls through to the guard rather than a blank page', async ({ page }) => {
    await page.goto(`${env.baseUrl}/no-such-page`);
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fdashboard$/);
  });

  test('an expired token counts as signed out', async ({ page }) => {
    await page.goto(`${env.baseUrl}/login`);
    // A well-formed token whose exp is in the past. The guard reads exp only.
    const expired = await page.evaluate(() => {
      const b64 = (o: object) => btoa(JSON.stringify(o)).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_');
      return `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ sub: 'x', exp: 1, roles: ['CUSTOMER'] })}.sig`;
    });
    await page.evaluate((t) => localStorage.setItem('lb_access_token', t), expired);

    await page.goto(`${env.baseUrl}/blotter`);
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fblotter$/);
  });

  test('a signed-in trader is kept out of the admin area', async ({ page }) => {
    await signInAsTrader(page);

    for (const route of ADMIN_ROUTES) {
      await page.goto(`${env.baseUrl}${route}`);
      await expect(page, `${route} should send a trader to the dashboard`).toHaveURL(/\/dashboard$/);
    }
  });

  test('a signed-in trader reaches every trader page', async ({ page }) => {
    await signInAsTrader(page);

    for (const route of TRADER_ROUTES) {
      await page.goto(`${env.baseUrl}${route}`);
      await expect(page).toHaveURL(new RegExp(`${route}$`));
    }
  });

  test('a return address off this origin is never followed (open redirect)', async ({ page }) => {
    for (const hostile of ['https://evil.example.com', '//evil.example.com', '/\\evil.example.com']) {
      await page.goto(`${env.baseUrl}/login?returnUrl=${encodeURIComponent(hostile)}`);
      await page.evaluate(() => localStorage.clear());
      await page.getByTestId('username-input').fill(env.username);
      await page.getByTestId('password-input').fill(env.password);
      await page.getByTestId('login-submit').click();

      await expect(page, hostile).toHaveURL(new RegExp(`^${env.baseUrl}/dashboard`));
    }
  });
});

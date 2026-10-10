import { test, expect } from '@playwright/test';
import { env } from './env';
import { signInAsAdmin } from './support';

/**
 * E2E journey: the admin area as a whole, against the real services.
 *
 * admin-approvals.spec.ts drives the approval console's lifecycle actions; this
 * file covers getting in, moving around, and the pages an administrator reads.
 * Nothing here changes data, so every test can run in any order.
 */

test.describe('Admin area', () => {
  test('an administrator signing in lands in the admin area, not the trader dashboard', async ({ page }) => {
    await signInAsAdmin(page);
    await expect(page).toHaveURL(/\/admin/);
  });

  test('a guarded admin page is reached after sign-in, carrying the return address', async ({ page }) => {
    await page.goto(`${env.baseUrl}/admin/accounts`);
    await expect(page).toHaveURL(/\/login\?returnUrl=%2Fadmin%2Faccounts$/);

    await page.getByTestId('username-input').fill(env.adminUsername);
    await page.getByTestId('password-input').fill(env.adminPassword);
    await page.getByTestId('login-submit').click();

    await expect(page).toHaveURL(/\/admin\/accounts$/, { timeout: 15_000 });
  });

  test('the sidebar has every admin page and no Instruments page', async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto(`${env.baseUrl}/admin/overview`);

    const links = page.locator('.nav-item');
    await expect(links).toHaveText(['Overview', 'Accounts', 'Approvals', 'Users', 'Orders']);
    await expect(page.getByRole('link', { name: /instruments/i })).toHaveCount(0);

    // The old address no longer exists; it falls through to the trader routes.
    await page.goto(`${env.baseUrl}/admin/instruments`);
    await expect(page).not.toHaveURL(/instruments/);
  });

  test('each sidebar link opens its page with a matching title', async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto(`${env.baseUrl}/admin/overview`);

    const pages: [string, RegExp, string][] = [
      ['Accounts', /\/admin\/accounts$/, 'Account Management'],
      ['Approvals', /\/admin\/approvals$/, 'Approval Console'],
      ['Users', /\/admin\/users$/, 'User Directory'],
      ['Orders', /\/admin\/orders$/, 'Platform Order Book'],
      ['Overview', /\/admin\/overview$/, 'Platform Overview'],
    ];
    for (const [link, url, title] of pages) {
      await page.locator('.nav-item', { hasText: link }).click();
      await expect(page).toHaveURL(url);
      await expect(page.locator('.topbar-title')).toHaveText(title);
    }
  });

  test('every applicant awaiting review has Approve and Reject in view', async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto(`${env.baseUrl}/admin/approvals`);
    await expect(page.locator('.admin-loading')).toHaveCount(0, { timeout: 15_000 });

    const rows = page.locator('.queue-panel tbody tr');
    const count = await rows.count();
    test.skip(count === 0, 'no applicants are waiting for approval');
    for (let i = 0; i < count; i++) {
      await expect(rows.nth(i).getByRole('button', { name: 'Approve' })).toBeInViewport();
      await expect(rows.nth(i).getByRole('button', { name: 'Reject' })).toBeVisible();
    }

    // Opening the detail panel must not push the actions out of view.
    await rows.first().locator('td').first().click();
    await expect(page.locator('.detail-panel')).toBeVisible();
    await expect(rows.first().getByRole('button', { name: 'Approve' })).toBeInViewport();
  });

  test('approving asks for confirmation first, and can be backed out of', async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto(`${env.baseUrl}/admin/approvals`);
    await expect(page.locator('.admin-loading')).toHaveCount(0, { timeout: 15_000 });
    const row = page.locator('.queue-panel tbody tr').first();
    test.skip((await row.count()) === 0, 'no applicants are waiting for approval');

    await row.getByRole('button', { name: 'Approve' }).click();
    await expect(page.locator('.action-title')).toContainText('Approve');
    await page.getByRole('button', { name: /cancel/i }).click();
    await expect(page.locator('.action-title')).toHaveCount(0);
  });

  test('the order book filters by status', async ({ page }) => {
    await signInAsAdmin(page);
    await page.goto(`${env.baseUrl}/admin/orders`);
    await expect(page.locator('.admin-loading')).toHaveCount(0, { timeout: 15_000 });

    const reload = page.waitForRequest((r) => r.url().includes('/api/v1/admin/orders') && r.url().includes('status=REJECTED'));
    await page.locator('select').first().selectOption('REJECTED');
    await reload;
    const badges = await page.locator('tbody .badge').allTextContents();
    for (const b of badges) expect(b.trim()).toBe('REJECTED');
  });

  test('a trader is refused by the admin API even with a valid session', async ({ page, request }) => {
    // The guard is only a usability control; the Trade REST API is the authority.
    const res = await request.post(`${env.authApi}/auth/login`, {
      data: { identifier: env.username, password: env.password },
    });
    const { accessToken } = (await res.json()) as { accessToken: string };
    const admin = await request.get(`${env.tradeApi}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${accessToken}` },
    });
    expect([401, 403]).toContain(admin.status());
  });

});

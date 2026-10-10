import { test, expect, type Page, type APIRequestContext } from '@playwright/test';
import { env } from './env';

/**
 * E2E journey: the admin approval console as an administrator uses it.
 *
 * Exercises the console the way a person does: open the queue, search it, switch
 * tabs, open a confirm step, and send an action. These are the paths that cannot
 * be covered by the API-level lifecycle tests, because they depend on the tab
 * filter and the search box actually re-filtering the list.
 *
 * Requires the seeded admin and the demo user to exist:
 * E2E_ADMIN_USERNAME, E2E_ADMIN_PASSWORD, E2E_USERNAME, E2E_PASSWORD.
 */

const BASE_URL = env.baseUrl;
const AUTH_API = env.authApi;
const TRADE_API = env.tradeApi;

async function adminToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`${AUTH_API}/auth/login`, {
    data: { identifier: env.adminUsername, password: env.adminPassword },
  });
  expect(res.ok(), 'admin login should succeed').toBeTruthy();
  return ((await res.json()) as { accessToken: string }).accessToken;
}

/** Signs in as the administrator and lands on the approval console. */
async function openConsole(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/login`);
  await page.evaluate(() => localStorage.clear());
  await page.getByTestId('username-input').fill(env.adminUsername);
  await page.getByTestId('password-input').fill(env.adminPassword);
  await page.getByTestId('login-submit').click();
  // Wait for the session to land before navigating on, otherwise the admin
  // guard sees no token yet and bounces back to the login form.
  await expect(page).not.toHaveURL(/\/login/, { timeout: 15000 });

  await page.goto(`${BASE_URL}/admin/approvals`);
  await expect(page.locator('.panel-title', { hasText: 'Approval console' })).toBeVisible({ timeout: 15000 });
  await expect(page.locator('.admin-loading')).toHaveCount(0, { timeout: 15000 });
}

test.describe('Admin approval console', () => {
  test('the pending tab shows pending applicants and nothing else', async ({ page }) => {
    await openConsole(page);

    await page.locator('.queue-tab').first().click();
    const badges = await page.locator('.queue-panel tbody .tone-badge').allTextContents();
    // Each row has a risk badge then a status badge.
    const statuses = badges.filter((_, i) => i % 2 === 1);
    expect(statuses.every((s) => s.trim() === 'INACTIVE')).toBe(true);
  });

  test('the search box narrows the visible rows', async ({ page }) => {
    await openConsole(page);

    await page.locator('.queue-tab').first().click();
    await page.locator('input[type="search"]').fill(env.username);
    await expect(page.locator('.queue-panel tbody tr')).toHaveCount(0);

    await page.locator('input[type="search"]').fill('');
    await page.locator('.queue-tab', { hasText: 'Active' }).first().click();
    await page.locator('input[type="search"]').fill(env.username);
    await expect(page.locator('.queue-panel tbody tr')).toHaveCount(1);
    await expect(page.locator('.queue-panel tbody tr')).toContainText(env.username);
  });

  test('suspending an active user moves them to the suspended queue', async ({ page }) => {
    await openConsole(page);
    const token = await adminToken(page.request);

    const users = await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    });
    const all = (await users.json()) as { userId: number; username: string; status: string }[];
    const target = all.find((u) => u.username === env.username);
    expect(target, 'the demo user should be active before this test').toBeTruthy();

    // Scoped to the row's action cell: an unscoped /suspend/i match also hits the
    // "Suspended" queue tab, which silently changes the filter instead.
    const row = page.locator('.queue-row', { hasText: env.username });
    await page.locator('input[type="search"]').fill(env.username);
    await page.locator('.queue-tab', { hasText: 'Active' }).first().click();
    await expect(row).toHaveCount(1);
    await row.click();

    // A destructive action must be confirmed with a reason before it is sent.
    await row.getByRole('button', { name: 'Suspend' }).click();
    await expect(page.locator('.action-title')).toContainText('Suspend');
    await expect(page.getByText('Reason (required)')).toBeVisible();

    await page.locator('#action-reason').fill('E2E console suspension');
    await page.getByRole('button', { name: /Confirm suspend/i }).click();
    await expect(page.locator('.success-banner')).toContainText(/suspend/i, { timeout: 15000 });

    // Back to the suspended tab, the same person is there.
    await page.locator('input[type="search"]').fill(env.username);
    await page.locator('.queue-tab', { hasText: 'Suspended' }).first().click();
    await expect(page.locator('.queue-panel tbody tr')).toHaveCount(1);

    // Reinstate so the demo user is left usable, and prove the balance is unchanged.
    const before = (await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.json())) as { userId: number; cashBalance: number | null }[];
    const balanceBefore = before.find((u) => u.userId === target!.userId)!.cashBalance;

    await page.locator('.queue-row', { hasText: env.username }).click();
    await page
      .locator('.queue-row', { hasText: env.username })
      .getByRole('button', { name: 'Reinstate' })
      .click();
    await expect(page.locator('.action-title')).toContainText('Reinstate');
    await page.getByRole('button', { name: /Confirm reinstate/i }).click();
    // Matched on text, not visibility: the suspension notice is still on screen
    // for a few seconds and would satisfy a bare visibility check immediately.
    await expect(page.locator('.success-banner')).toContainText(/is now active/i, { timeout: 15000 });

    const after = (await page.request.get(`${TRADE_API}/api/v1/admin/users`, {
      headers: { Authorization: `Bearer ${token}` },
    }).then((r) => r.json())) as { userId: number; status: string; cashBalance: number | null }[];
    const restored = after.find((u) => u.userId === target!.userId)!;
    expect(restored.status).toBe('ACTIVE');
    expect(restored.cashBalance, 'reinstatement must not pay the user again').toBe(balanceBefore);
  });

  test('the overview reports the onboarding pipeline and fill rate', async ({ page }) => {
    await openConsole(page);
    await page.goto(`${BASE_URL}/admin/overview`);

    await expect(page.locator('.admin-loading')).toHaveCount(0, { timeout: 15000 });
    await expect(page.locator('.admin-grid').first()).toBeVisible();

    // The lifecycle ring counts every registered identity across the gates, so
    // its segments must add up to the user total shown in the hole.
    const total = Number(
      (await page.locator('.donut-total').textContent())?.trim() ?? '0',
    );
    if (total > 0) {
      await expect(page.locator('.donut')).toBeVisible();
      const counts = await page.locator('.viz-legend .legend-value').allTextContents();
      const summed = counts.reduce((s, c) => s + (Number(c.trim()) || 0), 0);
      expect(summed, 'lifecycle segments should account for every user').toBe(total);
    }

    // Fill rate is a ratio, so it can never render above 100%.
    const fillRate = (await page.locator('.exec-bar').getAttribute('aria-label')) ?? '';
    const percent = Number(fillRate.match(/([\d.]+) percent/)?.[1] ?? '0');
    expect(percent).toBeGreaterThanOrEqual(0);
    expect(percent).toBeLessThanOrEqual(100);
  });

});
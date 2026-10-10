import { test, expect, type Page } from '@playwright/test';
import { env } from './env';

/**
 * E2E: the analysis page.
 *
 * Guards the two things that were wrong here. The page used to value holdings
 * from a hardcoded price table, so the allocation figures described a portfolio
 * nobody held; and a live quote that failed to load silently fell back to
 * invented numbers. Holdings are now valued at average cost x quantity, the
 * same as the dashboard and Holdings page, and live quotes only drive the return.
 */

const BASE_URL = env.baseUrl;

async function signIn(page: Page): Promise<void> {
  await page.goto(`${BASE_URL}/login`);
  await page.evaluate(() => localStorage.clear());
  await page.getByTestId('username-input').fill(env.username);
  await page.getByTestId('password-input').fill(env.password);
  await page.getByTestId('login-submit').click();
  await expect(page).toHaveURL(/\/dashboard/, { timeout: 15000 });
}

test.describe('Analysis', () => {
  test('renders metrics, criteria and an allocation ring', async ({ page }) => {
    await signIn(page);
    await page.goto(`${BASE_URL}/analysis`);

    await expect(page.locator('.analysis-loading')).toHaveCount(0, { timeout: 15000 });
    await expect(page.locator('.page-title')).toHaveText('Analysis');

    // Metrics come from the API, so they must be numbers rather than blanks.
    for (const label of ['Appetite alignment', 'Cash ratio', 'Fill rate']) {
      const card = page.locator('.metric-card', { hasText: label });
      await expect(card).toBeVisible();
      await expect(card.locator('.metric-value')).not.toHaveText('—');
    }

    // The three risk criteria are scored pass or fail, not decorative.
    const criteria = page.locator('.criteria li');
    await expect(criteria).toHaveCount(3);

    // The donut exists whenever there is anything to allocate.
    const donut = page.locator('.donut');
    if (await donut.count()) {
      await expect(donut).toBeVisible();
      await expect(page.locator('.alloc-legend li').first()).toBeVisible();
      await expect(page.locator('.donut-total')).toBeVisible();
    } else {
      await expect(page.locator('.alloc-empty')).toBeVisible();
    }
  });

  test('switching risk appetite re-scores the criteria', async ({ page }) => {
    await signIn(page);
    await page.goto(`${BASE_URL}/analysis`);
    await expect(page.locator('.analysis-loading')).toHaveCount(0, { timeout: 15000 });

    const readCriteria = async () =>
      (await page.locator('.criteria li .criteria-detail').allTextContents()).join(' | ');

    const chip = page.locator('.risk-card .chip');

    await page.locator('.appetite-switch button', { hasText: 'Aggressive' }).click();
    // Assert the target actually moved before reading the criteria, otherwise a
    // stale read looks like a genuine scoring difference.
    await expect(chip).toContainText('Aggressive');
    const aggressive = await readCriteria();

    await page.locator('.appetite-switch button', { hasText: 'Conservative' }).click();
    await expect(chip).toContainText('Conservative');
    const conservative = await readCriteria();

    // The targets differ per appetite, so the same portfolio cannot match both.
    expect(conservative).not.toBe(aggressive);
    expect(conservative).toContain('target 40–80%');
  });

  test('live prices for the return are read through the Trade REST API for every holding', async ({ page }) => {
    await signIn(page);

    const quotes: string[] = [];
    page.on('request', (req) => {
      if (/\/api\/v1\/instruments\/[^/]+\/quote/.test(req.url())) quotes.push(req.url());
    });

    await page.goto(`${BASE_URL}/analysis`);
    await expect(page.locator('.analysis-loading')).toHaveCount(0, { timeout: 15000 });

    const positionCount = Number(
      (await page.locator('.metric-card', { hasText: 'Open positions' })
        .locator('.metric-value')
        .textContent())?.trim() ?? '0',
    );

    if (positionCount > 0) {
      expect(
        quotes.length,
        'each held symbol should be priced by the quote service',
      ).toBeGreaterThanOrEqual(positionCount);
    }
  });

  test('every card explains itself on hover and on keyboard focus', async ({ page }) => {
    await signIn(page);
    await page.goto(`${BASE_URL}/analysis`);
    await expect(page.locator('.analysis-loading')).toHaveCount(0, { timeout: 15000 });

    const cards = page.locator('.metric-card');
    await expect(cards).toHaveCount(6);
    for (let i = 0; i < 6; i++) {
      const card = cards.nth(i);
      const tip = card.locator('[role=tooltip]');
      await card.hover();
      await expect(tip).toBeVisible();
    }

    // Keyboard users get the same explanation.
    const first = cards.first();
    await page.mouse.move(0, 0);
    await first.focus();
    await expect(first.locator('[role=tooltip]')).toBeVisible();
  });

  test('a failing quote leaves the cards on cost, with no return invented', async ({ page }) => {
    await signIn(page);
    await page.route('**/api/v1/instruments/*/quote', (route) => route.abort('failed'));
    await page.goto(`${BASE_URL}/analysis`);
    await expect(page.locator('.analysis-loading')).toHaveCount(0, { timeout: 15000 });

    const ret = page.locator('.metric-card', { hasText: 'Portfolio return' }).locator('.metric-value');
    await expect(ret).toHaveText('—');
    await expect(page.locator('.metric-card', { hasText: 'Cash ratio' }).locator('.metric-value')).toHaveText(/%$/);
  });
});
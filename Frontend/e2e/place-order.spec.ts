import { test, expect, type Page } from '@playwright/test';
import { env } from './env';

/**
 * E2E Journey: Place an order, against the real Trade REST API.
 * Covers:
 * - The account field is read-only and is the account the token says this session trades
 * - Client-side validation rejects bad input before anything reaches the wire
 * - A placed order comes back NEW, FILLED or REJECTED, never a blank screen
 *
 * Each test signs in from a clean context, and any order it places is cancelled
 * afterwards so no working order is left behind for another journey to trip over.
 */

const ORDERS_URL = `${env.tradeApi}/api/v1/orders`;

async function signIn(page: Page): Promise<void> {
  await page.goto(`${env.baseUrl}/login`);
  await page.getByTestId('username-input').fill(env.username);
  await page.getByTestId('password-input').fill(env.password);
  await page.getByTestId('login-submit').click();
  await page.waitForURL(/\/dashboard/);
}

async function fillLimitOrder(page: Page, price = '1.00'): Promise<void> {
  await page.locator('#symbol').fill(env.symbol);
  await page.locator('select#side').selectOption('BUY');
  await page.locator('#quantity').fill('1');
  await page.locator('#price').fill(price);
}

/** Cancels an order this journey placed. A FILLED or REJECTED order is already final. */
async function cancelIfWorking(page: Page, orderId: string | undefined): Promise<void> {
  if (!orderId) return;
  const token = await page.evaluate(() => localStorage.getItem('lb_access_token'));
  await page.request.delete(`${ORDERS_URL}/${encodeURIComponent(orderId)}`, {
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  });
}

test.describe('Place order journey', () => {
  let placedOrderId: string | undefined;

  test.beforeEach(async ({ page }) => {
    placedOrderId = undefined;
    await signIn(page);
    await page.goto(`${env.baseUrl}/order`);
  });

  test.afterEach(async ({ page }) => {
    await cancelIfWorking(page, placedOrderId);
  });

  test('the account field is read-only and shows the account the session may trade', async ({ page }) => {
    const accountField = page.locator('input#account');
    await expect(accountField).toBeVisible();
    await expect(accountField).toHaveAttribute('readonly', '');
    await expect(accountField).toBeDisabled();
    await expect(accountField).toHaveValue(env.accountId);
  });

  test('the account sent to the server is the token claim, not a field the user can change', async ({ page }) => {
    // Tamper with the DOM: a client that let this through would trade against
    // somebody else's account.
    await page.locator('input#account').evaluate((el: HTMLInputElement) => {
      el.removeAttribute('disabled');
      el.removeAttribute('readonly');
      el.value = '999999';
      el.dispatchEvent(new Event('input', { bubbles: true }));
    });

    await fillLimitOrder(page);
    const request = page.waitForRequest((r) => r.url() === ORDERS_URL && r.method() === 'POST');
    const response = page.waitForResponse((r) => r.url() === ORDERS_URL && r.request().method() === 'POST');
    await page.getByTestId('submit-order').click();

    const sent = (await request).postDataJSON() as { accountId?: number };
    expect(String(sent.accountId)).toBe(env.accountId);

    const body = await (await response).json();
    placedOrderId = body.orderId;
  });

  test('invalid input is rejected before anything reaches the wire', async ({ page }) => {
    let posted = false;
    page.on('request', (r) => {
      if (r.url() === ORDERS_URL && r.method() === 'POST') posted = true;
    });

    await page.locator('#symbol').fill('!!!');
    await page.locator('#quantity').fill('0');
    await page.locator('#price').fill('25.999');
    await page.getByTestId('submit-order').click();

    await expect(page.locator('.field-error').first()).toBeVisible();
    expect(await page.locator('.field-error').count()).toBeGreaterThanOrEqual(2);
    expect(posted).toBe(false);
  });

  test('a placed order comes back NEW, FILLED or REJECTED', async ({ page }) => {
    await fillLimitOrder(page);

    const response = page.waitForResponse((r) => r.url() === ORDERS_URL && r.request().method() === 'POST');
    await page.getByTestId('submit-order').click();
    const placed = await response;
    expect(placed.ok()).toBe(true);
    placedOrderId = (await placed.json()).orderId;

    await expect(page.locator('.error-banner')).toHaveCount(0);
    await expect(page.locator('.success-panel .badge')).toHaveText(/NEW|FILLED|REJECTED/);
  });

  test('the summary box appears once symbol and quantity are entered, for a buy', async ({ page }) => {
    const summary = page.getByTestId('trade-summary');
    await expect(summary).toHaveCount(0);

    await page.locator('#symbol').fill(env.symbol);
    await page.locator('#quantity').fill('2');
    await page.locator('#price').fill('10.00');

    await expect(summary).toBeVisible();
    await expect(summary).toContainText(env.symbol);
    await expect(summary).toContainText('BUY');
    await expect(summary).toContainText('Estimated cost');
    // 2 x 10.00 at the limit, in the account currency.
    await expect(summary.getByTestId('summary-total')).toContainText('20.00');
    await expect(summary).toContainText('held');
  });

  test('the summary box shows proceeds for a sell', async ({ page }) => {
    await page.locator('#symbol').fill(env.symbol);
    await page.locator('select#side').selectOption('SELL');
    await page.locator('#quantity').fill('1');
    await page.locator('#price').fill('5.00');

    const summary = page.getByTestId('trade-summary');
    await expect(summary).toContainText('SELL');
    await expect(summary).toContainText('Estimated proceeds');
    await expect(summary.getByTestId('summary-total')).toContainText('5.00');
  });

  test('a market order shows one price and the live price in the summary', async ({ page }) => {
    await page.getByTestId('order-type-market').click();
    await expect(page.locator('#price')).toHaveCount(0);

    await page.locator('#symbol').fill(env.symbol);
    await page.locator('#quantity').fill('1');

    const summary = page.getByTestId('trade-summary');
    await expect(summary).toContainText('MARKET');
    await expect(summary.getByTestId('summary-market-price')).not.toContainText('loading', { timeout: 15_000 });
  });

  test('a sell beyond the holding is sent without any client-side check: the executor decides', async ({ page }) => {
    await page.locator('#symbol').fill(env.symbol);
    await page.locator('select#side').selectOption('SELL');
    await page.locator('#quantity').fill('999999');
    await page.locator('#price').fill('0.01');

    // No warning and no refusal in the UI: holdings are checked at fill time only.
    await expect(page.getByTestId('summary-warning')).toHaveCount(0);

    const response = page.waitForResponse((r) => r.url() === ORDERS_URL && r.request().method() === 'POST');
    await page.getByTestId('submit-order').click();
    const placed = await response;
    // Accepted, not refused with ORD-409: holdings are checked at fill time.
    expect(placed.ok(), 'the API must not refuse a sell for holdings any more').toBe(true);
    await expect(page.locator('.error-banner')).toHaveCount(0);
    placedOrderId = (await placed.json()).orderId;
    await expect(page.locator('.success-panel .badge')).toHaveText(/NEW|REJECTED/);
  });
});

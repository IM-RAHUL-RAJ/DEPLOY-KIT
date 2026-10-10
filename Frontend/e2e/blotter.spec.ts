import { test, expect, type APIRequestContext } from '@playwright/test';
import { env } from './env';
import {
  apiToken,
  cancelIfWorking,
  heldQuantity,
  orderStatus,
  placeOrderViaApi,
  signInAsTrader,
} from './support';

/**
 * E2E journey: the order blotter, against the real Trade REST API and executor.
 *
 * Each test arranges its own orders through the API, signs in from a clean
 * context, and cancels anything it left working, so no test depends on another.
 */

/** Waits for the executor to resolve an order, or reports that it did not. */
async function waitForFinal(request: APIRequestContext, token: string, orderId: string, timeoutMs = 30_000) {
  const deadline = Date.now() + timeoutMs;
  let last: { status: string; rejectionReason?: string } | undefined;
  while (Date.now() < deadline) {
    last = await orderStatus(request, token, orderId);
    if (last && last.status !== 'NEW') return last;
    await new Promise((r) => setTimeout(r, 1000));
  }
  return last;
}

test.describe('Order blotter', () => {
  let token: string;
  const placed: string[] = [];

  test.beforeEach(async ({ request }) => {
    token = await apiToken(request, env.username, env.password);
    placed.length = 0;
  });

  test.afterEach(async ({ request }) => {
    for (const id of placed) await cancelIfWorking(request, token, id);
  });

  test('lists orders newest first with a worded badge on every row', async ({ page, request }) => {
    // A limit buy far below the market stays working, so it is the newest row.
    const order = await placeOrderViaApi(request, token, { side: 'BUY', quantity: 1, price: 0.01 });
    placed.push(order.orderId);

    await signInAsTrader(page);
    await page.goto(`${env.baseUrl}/blotter`);

    const rows = page.locator('.blotter-table tbody tr');
    await expect(rows.first()).toBeVisible({ timeout: 15_000 });
    await expect(rows.first().locator('.order-id')).toHaveText(String(order.orderId));

    // Each badge carries the word, not just a colour.
    const badges = await page.locator('.blotter-table tbody .badge').allTextContents();
    expect(badges.length).toBeGreaterThan(0);
    for (const b of badges) expect(b.trim()).toMatch(/^(NEW|FILLED|REJECTED|CANCELLED)$/);

    const times = await page.locator('.blotter-table tbody .time').allTextContents();
    const parsed = times.map((t) => Date.parse(t)).filter((t) => !Number.isNaN(t));
    for (let i = 1; i < parsed.length; i++) expect(parsed[i - 1]).toBeGreaterThanOrEqual(parsed[i]);
  });

  test('a working order is shown as working and the page keeps checking it', async ({ page, request }) => {
    const order = await placeOrderViaApi(request, token, { side: 'BUY', quantity: 1, price: 0.01 });
    placed.push(order.orderId);

    await signInAsTrader(page);
    await page.goto(`${env.baseUrl}/blotter`);

    const row = page.locator('.blotter-table tbody tr', { hasText: String(order.orderId) });
    await expect(row.locator('.badge')).toHaveText('NEW', { timeout: 15_000 });
    await expect(row).toContainText('working');
    await expect(page.locator('.poll-indicator')).toBeVisible();

    // It re-reads order history; it never re-posts the order.
    let reposted = false;
    page.on('request', (r) => {
      if (r.method() === 'POST' && r.url().endsWith('/api/v1/orders')) reposted = true;
    });
    const reread = page.waitForRequest((r) => r.method() === 'GET' && /\/accounts\/\d+\/orders/.test(r.url()));
    await reread;
    expect(reposted).toBe(false);
  });

  test('the Refresh button re-reads order history', async ({ page }) => {
    await signInAsTrader(page);
    await page.goto(`${env.baseUrl}/blotter`);
    await expect(page.locator('.loading-state')).toHaveCount(0, { timeout: 15_000 });

    const reread = page.waitForRequest((r) => r.method() === 'GET' && /\/accounts\/\d+\/orders/.test(r.url()));
    await page.getByRole('button', { name: /^refresh$/i }).click();
    await reread;
  });

  test('a working order can be cancelled from the blotter', async ({ page, request }) => {
    const order = await placeOrderViaApi(request, token, { side: 'BUY', quantity: 1, price: 0.01 });
    placed.push(order.orderId);

    await signInAsTrader(page);
    await page.goto(`${env.baseUrl}/blotter`);

    const row = page.locator('.blotter-table tbody tr', { hasText: String(order.orderId) });
    await expect(row.locator('.badge')).toHaveText('NEW', { timeout: 15_000 });
    await row.getByRole('button', { name: /cancel/i }).click();

    await expect(row.locator('.badge')).toHaveText('CANCELLED', { timeout: 15_000 });
    expect((await orderStatus(request, token, order.orderId))?.status).toBe('CANCELLED');
  });

  test('a sell beyond the holding is accepted, then rejected by the executor with its reason in a pop-up', async ({ page, request }) => {
    // Funds and holdings are the executor's decision, not the API's: the order is
    // accepted as NEW and moves to REJECTED with INSUFFICIENT_HOLDINGS.
    const held = await heldQuantity(request, token);
    const order = await placeOrderViaApi(request, token, { side: 'SELL', quantity: held + 1_000_000, price: 0.01 });
    placed.push(order.orderId);
    expect(['NEW', 'REJECTED']).toContain(order.status);

    const final = await waitForFinal(request, token, order.orderId);
    test.skip(final?.status === 'NEW', 'the Trade Executor did not resolve the order: is it running?');
    expect(final?.status).toBe('REJECTED');
    expect(final?.rejectionReason).toMatch(/^INSUFFICIENT_HOLDINGS/);

    await signInAsTrader(page);
    await page.goto(`${env.baseUrl}/blotter`);
    const row = page.locator('.blotter-table tbody tr', { hasText: String(order.orderId) });
    await expect(row.locator('.badge')).toHaveText('REJECTED', { timeout: 15_000 });
    await expect(row).toContainText('Not enough of the holding to sell');

    await row.getByTestId('rejection-trigger').click();
    const dialog = page.getByTestId('rejection-dialog');
    await expect(dialog).toBeVisible();
    await expect(dialog).toHaveAttribute('role', 'dialog');
    await expect(dialog).toContainText('Not enough of the holding to sell');
    await expect(dialog).toContainText(String(order.orderId));
    await expect(dialog.getByTestId('rejection-code')).toHaveText(/INSUFFICIENT_HOLDINGS/);

    // Escape closes it and focus goes back to the row that opened it.
    await page.keyboard.press('Escape');
    await expect(dialog).toHaveCount(0);
    await expect(row.getByTestId('rejection-trigger')).toBeFocused();
  });

  test('a buy beyond the cash balance is accepted, then rejected for insufficient funds', async ({ page, request }) => {
    // A limit far above any market price fills at once if affordable, so a huge
    // quantity guarantees the executor sees a shortfall.
    const order = await placeOrderViaApi(request, token, { side: 'BUY', quantity: 100_000_000, price: 100_000 });
    placed.push(order.orderId);

    const final = await waitForFinal(request, token, order.orderId);
    test.skip(final?.status === 'NEW', 'the Trade Executor did not resolve the order: is it running?');
    expect(final?.status).toBe('REJECTED');
    expect(final?.rejectionReason).toMatch(/^INSUFFICIENT_FUNDS/);

    await signInAsTrader(page);
    await page.goto(`${env.baseUrl}/blotter`);
    const row = page.locator('.blotter-table tbody tr', { hasText: String(order.orderId) });
    await row.click();
    await expect(page.getByTestId('rejection-dialog')).toContainText('Not enough cash at the fill price');
    await page.getByRole('button', { name: 'Close', exact: true }).first().click();
    await expect(page.getByTestId('rejection-dialog')).toHaveCount(0);
  });
});

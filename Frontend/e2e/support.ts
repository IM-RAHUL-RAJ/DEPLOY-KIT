import { expect, type APIRequestContext, type Page } from '@playwright/test';
import { env } from './env';

/**
 * Helpers shared by the journeys. Nothing here holds state between tests: every
 * helper takes the page or request context of the test that calls it, so each
 * journey still starts from a clean browser and stands on its own.
 */

export const ORDERS_URL = `${env.tradeApi}/api/v1/orders`;

/** Signs in through the UI, the way a person does, and waits for the session to land. */
export async function signInThroughUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto(`${env.baseUrl}/login`);
  await page.evaluate(() => localStorage.clear());
  await page.getByTestId('username-input').fill(username);
  await page.getByTestId('password-input').fill(password);
  await page.getByTestId('login-submit').click();
  await expect(page).not.toHaveURL(/\/login/, { timeout: 15_000 });
}

export const signInAsTrader = (page: Page) => signInThroughUi(page, env.username, env.password);
export const signInAsAdmin = (page: Page) => signInThroughUi(page, env.adminUsername, env.adminPassword);

/** A bearer token straight from the auth service, for arranging and cleaning up state. */
export async function apiToken(request: APIRequestContext, username: string, password: string): Promise<string> {
  const res = await request.post(`${env.authApi}/auth/login`, { data: { identifier: username, password } });
  expect(res.ok(), `sign-in as ${username} should succeed`).toBeTruthy();
  return ((await res.json()) as { accessToken: string }).accessToken;
}

export interface PlacedOrder {
  orderId: string;
  status: string;
}

/** Places an order through the Trade REST API as the trader. */
export async function placeOrderViaApi(
  request: APIRequestContext,
  token: string,
  order: { side: 'BUY' | 'SELL'; quantity: number; price: number; symbol?: string },
): Promise<PlacedOrder> {
  const res = await request.post(ORDERS_URL, {
    headers: { Authorization: `Bearer ${token}` },
    data: {
      accountId: Number(env.accountId),
      symbol: order.symbol ?? env.symbol,
      side: order.side,
      orderType: 'LIMIT',
      quantity: order.quantity,
      price: order.price,
      idempotencyKey: `e2e-${Date.now()}-${Math.random().toString(36).slice(2)}`,
    },
  });
  expect(res.ok(), `placing the order should be accepted: ${await res.text()}`).toBeTruthy();
  return (await res.json()) as PlacedOrder;
}

/** Reads one order's current status from order history. */
export async function orderStatus(
  request: APIRequestContext,
  token: string,
  orderId: string,
): Promise<{ status: string; rejectionReason?: string } | undefined> {
  const res = await request.get(`${env.tradeApi}/api/v1/accounts/${env.accountId}/orders`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const orders = (await res.json()) as { orderId: string; status: string; rejectionReason?: string }[];
  return orders.find((o) => String(o.orderId) === String(orderId));
}

/** Cancels an order a journey placed, if it is still working. A final order is left alone. */
export async function cancelIfWorking(request: APIRequestContext, token: string, orderId?: string): Promise<void> {
  if (!orderId) return;
  await request.delete(`${ORDERS_URL}/${encodeURIComponent(orderId)}`, {
    headers: { Authorization: `Bearer ${token}` },
    failOnStatusCode: false,
  });
}

/** The trader's current holding of a symbol, 0 when none. */
export async function heldQuantity(request: APIRequestContext, token: string, symbol = env.symbol): Promise<number> {
  const res = await request.get(`${env.tradeApi}/api/v1/accounts/${env.accountId}/positions`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const positions = (await res.json()) as { symbol: string; quantity: number }[];
  return positions.find((p) => p.symbol.toUpperCase() === symbol.toUpperCase())?.quantity ?? 0;
}

import { defineConfig, devices } from '@playwright/test';

/**
 * Playwright configuration for the Sprint 9 trading UI.
 *
 * Every address and credential is read from the environment so all specs run
 * against one set of services. The two journeys live in ./e2e and are run one
 * file at a time against the real running stack.
 */
export default defineConfig({
  testDir: './e2e',
  // The assessed suite: sign-in, route guards, placing an order, the blotter and
  // the admin area. Analysis and onboarding are extra journeys, run with
  // E2E_EXTRA=1 (onboarding also reads the auth-service log for the OTP).
  testIgnore: process.env['E2E_EXTRA'] ? [] : ['**/analysis.spec.ts', '**/onboarding.spec.ts'],
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env['CI'],
  retries: process.env['CI'] ? 1 : 0,
  // List output in the terminal, plus a self-contained HTML report in
  // playwright-report/ (open it with `npm run e2e:report`). Never auto-opened,
  // so a run in CI or a script does not block on a browser.
  reporter: [
    ['list'],
    ['html', { outputFolder: process.env['E2E_REPORT_DIR'] ?? 'playwright-report', open: 'never' }],
  ],
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: process.env['E2E_BASE_URL'] ?? 'http://localhost:4200',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});

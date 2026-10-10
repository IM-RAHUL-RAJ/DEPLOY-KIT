# Sprint 9: Trading App UI

Angular 21 trading UI: standalone components and signals throughout, no `NgModule`.

## Project structure
```
src/app/
  core/
    guards/          auth.guard.ts, admin.guard.ts – route guards; return-url.ts – same-origin return address
    interceptors/    auth.interceptor.ts – the only place an Authorization header is set
    services/        trade.service.ts, auth.service.ts – wrappers over the generated clients
                     token.service.ts – access token and claims
                     error-catalogue.ts – every contract error code -> trader-facing sentence
                     contract-error-codes.json – codes read out of the contracts (generated)
  features/
    auth/login, auth/register, shell, dashboard, order-ticket, blotter, holdings, analysis, admin
  app.routes.ts      route table (login and register are public, everything else guarded)
  app.config.ts      router, HttpClient + the interceptor, generated-client base URLs
e2e/                 Playwright journeys (login.spec.ts, place-order.spec.ts, ...)
generated/           committed generated clients (trade-api/, auth-api/) – never hand-edited
contracts/           generator input, copied from ../Contracts/API Schemas on every run
scripts/             contract sync, error-code extraction, bundle scan
```

## Generated API clients

The services call the Trade REST API and the Auth service only through clients
generated from the contracts. A field or path the contract changes is then a
compile error at every call site, not an `undefined` at runtime.

```bash
npm run generate             # both clients
npm run generate:trade-api   # one
npm run generate:auth-api
```

- **The source of truth is `../Contracts/API Schemas/`.** Edit the contract
  there. `npm run generate` copies it into `contracts/` first, because the
  generator's `$ref` resolver cannot handle the space in that path.
- **The generator and its options are pinned in `openapitools.json`.** It uses
  OpenAPI Generator 7.9.0, `typescript-angular`, with one entry per contract.
  It runs on the JVM.
- **`skipValidateSpec` is on deliberately.** The generator's OpenAPI 3.1
  validator demands `info.license.identifier`, which the specification makes
  optional and the contracts do not carry.
- **`generated/*/.openapi-generator-ignore` keeps `api.module.ts` out**, because
  it is an `NgModule`. Its `index.ts` barrel goes with it. The generated
  services are `providedIn: 'root'`, and their base URLs are set in
  `app.config.ts`.
- **`generated/` is committed and never edited by hand.** Regenerate on every
  contract change and commit the output with the code that adapts to it. Before
  a review, regenerate and run `git status`. Any change under `generated/` means
  the committed client was stale.

## Run

```bash
npm ci
npm start                    # http://localhost:4200
```

API addresses are in `src/environments/`. Nothing secret belongs there: every
file in the build is public.

## Tests

```bash
npm test                     # unit tests (Karma, ChromeHeadless)
npm run build && npm run bundle:scan
```

`npm test` first re-reads the error codes from both contracts. The catalogue
spec then fails if any of them has no message.

`bundle:scan` searches `dist/` for:
- market-data keys (`x-api-key`, `api_key`, `api-key`, `fauxnance`);
- `execute-api` hosts;
- `jwt_secret`;
- JWT literals;
- the literal values of the secrets in `../.env`.

It exits non-zero on any hit.

## All tests and reports

Every test type, its count, its command and where its report lands is listed in
[../PROJECT_GUIDE.md §9](../PROJECT_GUIDE.md#9-testing-and-reports). In short:
`npm test`, `npm run test:coverage`, `npm run test:integration`,
`npx playwright test` then `npm run e2e:report`.

## Playwright journeys

The journeys run against the real stack: every service up, and the seeded
database. Run each file in its own process, so a journey that only passes after
its neighbour fails here rather than at the review.

```bash
npx playwright test e2e/login.spec.ts
npx playwright test e2e/place-order.spec.ts
```

Every run writes an HTML report (results, plus screenshot, trace and video for
any failure) to `playwright-report/`, which is git-ignored. Open the latest one:

```bash
npm run e2e:report
```

Each run replaces the previous report. To keep one report per journey, give
each run its own folder:

```bash
E2E_REPORT_DIR=playwright-report/login npx playwright test e2e/login.spec.ts
E2E_REPORT_DIR=playwright-report/place-order npx playwright test e2e/place-order.spec.ts
npx playwright show-report playwright-report/login
```

Every address and credential comes from the environment (see
`e2e/.env.e2e.example`). Credentials have no default.

| Variable | Meaning |
|---|---|
| `E2E_BASE_URL` | Angular dev server origin (default `http://localhost:4200`) |
| `E2E_TRADE_API` | Trade REST API origin (default `http://localhost:8081`) |
| `E2E_AUTH_API` | Auth service origin (default `http://localhost:3000`) |
| `E2E_USERNAME` / `E2E_PASSWORD` | An approved, funded customer |
| `E2E_ACCOUNT_ID` | The account that customer may trade |
| `E2E_SYMBOL` | A tradable symbol (default `AAPL`) |
| `E2E_ADMIN_USERNAME` / `E2E_ADMIN_PASSWORD` | Admin login, for the admin and onboarding journeys |

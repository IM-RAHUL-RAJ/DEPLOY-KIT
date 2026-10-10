# Sprint 08 Auth Service

Standalone authentication service for **Sprint 8**, built with NestJS per the
`auth_api_yaml.txt` / `context.txt` contract. It is the **only** component that
touches passwords. No other service (Trade API, executor, ETL) can see the
user table or password hashes. Consumers validate JWT signature + issuer
locally with no network call.

## Responsibilities

- `POST /auth/register` **— step 1 of 2**. Validates the profile and emails a
  six-digit code. Creates **no user and no account**, so there is nothing to
  authenticate against until the code is confirmed. The account opens at
  `0.00`; an admin approves it and funds it separately.
- `POST /auth/register/verify` **— step 2 of 2**. Confirms the code and, only
  then, creates the customer and, **in the same transaction**, a
  `trading.account` (USD, `0.00`, status `INACTIVE`, version `1`).
  **Issues no tokens**; the client calls `POST /auth/login` afterwards.
- `POST /auth/register/resend` **—** issue a fresh code, which invalidates the
  previous one.
- `POST /auth/login` — authenticate with username or email + password. Identical body/status for "unknown user" and "wrong password". Throttled and time-uniform. Returns the access token and sets the `refresh_token` cookie.
- `POST /auth/refresh` — rotate the refresh token read from the **cookie** (or, as a fallback, the body); a presented token that was already rotated (replay) revokes its whole family.
- `POST /auth/logout` — revoke the current refresh token and clear the session cookie.
- `GET /auth/me` — current user profile + trading `accountId`, requires `Authorization: Bearer <accessToken>`.

The refresh token is **never returned in a JSON body and never stored in
localStorage** — the browser stores it as an `HttpOnly; Secure;
SameSite=Lax` cookie scoped to `Path=/`, and JavaScript never reads it.

## Route table

| Method | Path            | Auth  | Success                                                                                 | Errors                                          |
| ------ | --------------- | ----- | --------------------------------------------------------------------------------------- | ----------------------------------------------- |
| POST   | `/auth/register`| none  | `201` `{ message, email, challengeId, expiresInSeconds, emailDelivered }` — **no user, no account, no tokens**. `emailDelivered:false` means SMTP is unset and the code is in the service log | `409` `AUTH-409`, `422` `VAL-422` |
| POST   | `/auth/register/verify` | none | `201` `{ message, user: { userId, username, email, accountId, status } }` with `status: INACTIVE` — **no tokens**; sign in after approval | `404` `AUTH-404`, `410` `AUTH-410`, `429` `RATE-429`, `422` `VAL-422` |
| POST   | `/auth/register/resend` | none | `201` `{ message, emailDelivered, expiresInSeconds }`; the previous code stops working | `404` `AUTH-404`, `429` `RATE-429` |
| POST   | `/auth/login`   | none  | `200` `{ accessToken, expiresIn, user: {...} }` + sets `refresh_token` cookie | `401` `AUTH-401`, `403` `AUTH-403`, `429` `RATE-429`, `422` `VAL-422` |
| POST   | `/auth/refresh` | cookie| `200` same access body as login + rotated `refresh_token` cookie. Reads the token from the `refresh_token` cookie (body `refreshToken` still accepted as a fallback) | `401` `AUTH-401`, `422` `VAL-422` |
| POST   | `/auth/logout`  | cookie| `200` `{ message: "Logged out" }`; revokes the refresh token and clears the cookie (body `refreshToken` fallback supported) | `401` `AUTH-401`                               |
| GET    | `/auth/me`      | Bearer| `200` `{ userId, uuid, firstName, middleName, lastName, username, email, roles, accountId }` | `401` `AUTH-401`                              |
| GET    | `/docs`         | none  | Swagger UI                                                                               |                                                 |
| GET    | `/docs/json`    | none  | OpenAPI JSON                                                                             |                                                 |

The `refresh_token` cookie is `HttpOnly; Secure; SameSite=Lax; Path=/` with the
refresh lifetime (default 7 days), cleared on logout.

Every error is a JSON envelope `{ "errorCode": string, "message": string }`
with codes `AUTH-401`, `AUTH-409`, `VAL-422`, `RATE-429`, `SRV-500`.

## Run

Requirements: Node 20+ (tested on Node 24), PostgreSQL with the
`Databases/PostgreSQL/schema.sql` applied (schemas `auth` + `trading`), and
`JWT_SECRET` (32+ bytes) set in `sprint9/.env`: the service refuses to start
without it.

```bash
npm install
cp .env.example .env   # adjust DB credentials
npm run build
npm start              # listens on http://localhost:3000
```

Then open http://localhost:3000/docs for Swagger UI and
http://localhost:3000/docs/json for the OpenAPI document.

### Tests

```bash
npm test
```

Jest suites live next to the code (`*.spec.ts`) covering token signing/issuer
validation, login throttling, registration/validation rules and the auth
flows with a mocked database.

### Docker

```bash
docker build -t sprint08-auth-service .
docker run --rm -p 3000:3000 --env-file .env sprint08-auth-service
```

## Configuration

| Variable              | Default                                             | Purpose                                    |
| --------------------- | --------------------------------------------------- | ------------------------------------------ |
| `PORT`                | `3000`                                              | HTTP port                                  |
| `DB_HOST/PORT/USER/PASSWORD/NAME` | `localhost/5432/postgres/n3u3d4!/trading_system_db` | PostgreSQL connection (schemas `auth` + `trading`) |
| `JWT_SECRET`          | `akatsuki_akatsuki_akatsuki_akatsuki_akatsuki`      | HS256 signing key (also used by the Trade API) |
| `JWT_ISSUER`          | `auth-service`                                      | `iss` claim; the Trade API rejects other issuers |
| `JWT_TTL_SECONDS`     | `900` (15 min)                                      | Access-token lifetime                       |
| `REFRESH_TTL_SECONDS` | `604800` (7 days)                                   | Refresh-token lifetime                      |
| `THROTTLE_MAX_ATTEMPTS` / `THROTTLE_WINDOW_MS` | `5` / `900000`                       | Login throttle window                       |
| `COOKIE_NAME`         | `refresh_token`                                   | Name of the refresh-token cookie              |
| `COOKIE_SECURE`       | `true`                                            | Set `false` only for plain-HTTP demos on non-local hosts (browsers already honour `Secure` over `http://localhost`) |
| `COOKIE_SAMESITE`     | `lax`                                           | Cookie SameSite: `lax` (recommended), `strict`, or `none` |
| `SMTP_HOST`           | *(empty)*                                       | Verification-email host. **Empty means the OTP is written to the service log instead of sent**, and the response reports `emailDelivered: false` |
| `SMTP_PORT`           | `587`                                           | SMTP port                                   |
| `SMTP_USER` / `SMTP_PASS` | *(empty)*                                   | SMTP credentials; keep them in `.env`, never in git |
| `SMTP_FROM`           | *(empty)*                                       | Envelope sender used for the code email    |

## Security decisions (mapped to `security_template.txt`)

- **Token**: JWT HS256 with `sub` = user `uuid` (stable, never revealed as an
  auto-increment), `accountId` (trading account id), `roles` (always a
  non-empty array, defaults to `CUSTOMER`), `iss` = `auth-service`,
  `exp` = 15 min. No password material is ever embedded.
- **Passwords**: bcrypt cost **12** (`bcryptjs`). Registration requires
  ≥ 12 chars with upper/lower/digit/special via `class-validator`. Plaintext
  and hashes are never logged and never returned.
- **No self-declared roles**: registration inserts `ARRAY['CUSTOMER']`
  server-side; the payload plays no role in role assignment.
- **Refresh rotation + replay protection**: tokens stored as SHA-256 digests.
  A refresh rotates the token (old one revoked, new one in the same `family_id`).
  Presenting an already-rotated token revokes the whole family and returns `AUTH-401`.
- **Httponly cookies**: the refresh token is set as an `HttpOnly; Secure;
  SameSite=Lax` cookie (`Path=/`) and is never present in a JSON response,
  so JavaScript/XSS cannot read or exfiltrate it. The cookie is rotated on
  every refresh and cleared on logout.
- **Login timing**: a fixed 150 ms delay is added before every failed response so
  unknown-user and wrong-password cases are indistinguishable in time and body.
- **Login throttle**: in-memory per-identifier sliding window (default 5 / 15 min)
  returning `429 RATE-429`. (In-memory by design for the scope; note in
  production a shared store such as Redis is recommended.)
- **Validation**: `ValidationPipe` with whitelist + `class-validator`, mapping to
  `422 VAL-422` with a summary message.
- **Errors**: `ErrorEnvelopeFilter` rewrites every response to the contract
  `{ errorCode, message }` envelope — no stack traces or internals leak.

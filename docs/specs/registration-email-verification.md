# Registration Email Verification (Magic Link)

## Status

Implemented

## Overview / Purpose

Registration previously reused the same 6-digit OTP mechanism as login: after submitting the
registration form, the user was told a code had been sent, but the registration page itself had no
code-entry UI — completing signup meant separately navigating to `/login`, re-entering the email,
requesting a fresh code there, and typing it in. (`VerificationEmailModal`'s own copy already
promised "click the link in the email," a mismatch with what the backend actually sent — this
feature makes that copy true.)

Registration now sends a one-click verification **link** instead of a typed code. Clicking it
activates the account and logs the user straight into the dashboard, with no code entry required.
Login is unchanged — it still uses `/auth/otp/request` + `/auth/otp/verify` exactly as before.

## Requirements

- Submitting the registration form sends an email containing a verification link, not a code.
- Clicking the link activates the account and signs the user in directly to the dashboard — no
  separate login step.
- The link is valid for 24 hours and can only be used once.
- Resending (from `VerificationEmailModal`'s existing "Resend Email" button) issues a new link and
  invalidates the previous one.
- Login's OTP flow is untouched.

## Architecture Decisions

**A new, separate mechanism (`RegistrationVerificationToken` + `RegistrationVerificationService`),
not an extension of `OtpService`/`OtpVerification`.** A clicked link and a typed code are different
enough in shape and threat model to not share one table: the link token is a 32-byte opaque secret
(effectively unguessable, so no attempt-limiting is needed, unlike the 6-digit OTP's
`maxAttempts`/`OTP_LOCKED`), and its expiry is 24 hours vs. OTP's ~10 minutes — a single
`expiryMinutes`-style property can't sensibly serve both. Reusing `OtpVerification` would mean
branching most of its logic by "is this a link or a code," which is worse than two small, focused
services. Login keeps using `OtpService` exactly as before — this is intentionally *not* a shared
refactor of `OtpService`'s user-activation logic, to keep the blast radius on the already-shipped,
already-tested login path at zero.

**Token generation/hashing mirrors `RefreshTokenService` exactly** (32 random bytes,
base64url-encoded for the raw token; plain SHA-256 hex digest at rest, via `findByTokenHash`) rather
than `OtpHasher`'s HMAC scheme, which exists specifically to let a 6-digit *code* (low entropy) be
verified without a rainbow-table risk — irrelevant for a 256-bit random token, where plain hashing
of the token-as-stored-secret is the same tradeoff `RefreshTokenService` already accepted for an
analogous opaque-secret-at-rest case.

**Single-use via one `usedAt` timestamp, not a status enum.** Mirrors `RefreshToken`'s
`revokedAt`-style design (simpler than `OtpVerification`'s `OtpStatus` enum) since there's only one
real state transition (issued → consumed) plus expiry, which is just a timestamp comparison — no
`LOCKED`/`EXPIRED`-as-persisted-state needed. Requesting a new link (register again, or "Resend
Email") marks any still-unused previous token as used too, so only the newest link ever works —
consistent with `OtpService.supersedePendingOtp`'s same intent for codes. The verify error message
is deliberately generic ("no longer valid") rather than distinguishing "already used" from
"superseded by a newer request," since telling those apart isn't useful to the user and the codes
already exist (`REGISTRATION_TOKEN_INVALID` vs `REGISTRATION_TOKEN_EXPIRED`) if a future UI ever
wants to.

**"Resend" reuses `POST /auth/register` itself, not a new endpoint.** `UserService.registerUser`
already overwrites an unverified `PENDING_VERIFICATION` row in place rather than rejecting it as a
duplicate — resending is just calling register again with the same (or corrected) form values, which
also lets a user fix a typo'd field before the link they eventually click. `Registration`'s
"Resend Email" button now replays the full form instead of calling an email-only resend endpoint.

**New `app.frontend.url` config**, since no "the portal's base URL" property existed anywhere in this
backend before now — every prior email either had no links (OTP) or didn't need one. Defaults to
`http://localhost:4200` for local dev, matching how other new config in this codebase defaults
sensibly rather than requiring an env var to boot locally.

**The verify endpoint is `POST /auth/register/verify` with the token in the body, not a `GET`.** The
emailed link points at a *frontend* route (`/register/verify?token=...`); the SPA reads the query
param and calls the backend, matching this API's existing all-mutations-are-POST convention (and
`/auth/otp/verify`'s own shape) rather than making the backend directly handle a browser-navigated
GET.

## Data Model

New table `registration_verification_tokens` (`V12`): `id` (UUID), `user_id`, `token_hash` (unique),
`expires_at`, `used_at` (nullable — null means still valid), `created_at`.

## API Surface

- **`POST /auth/register`** — unchanged request/response shape (`RegisterRequest` →
  `OtpRequestResponse`). Now issues a verification link instead of an OTP code;
  `expiresInSeconds` reports the link's 24h TTL in seconds rather than the OTP's ~10 minutes, and
  `message` no longer mentions a code.
- **`POST /auth/register/verify`** *(new)* — `RegistrationVerifyRequest {token}` → `200`
  `LoginResponse` (same shape `/auth/otp/verify` returns: token pair + user profile + `firstLogin`).
  Unauthenticated (`security: []`, matches `/auth/register`). `400 REGISTRATION_TOKEN_INVALID` /
  `REGISTRATION_TOKEN_EXPIRED` for a bad/expired/already-used token.
- No change to `/auth/otp/*` (login) or their contracts.

## Security Considerations

- Token is a 256-bit random value, hashed at rest with plain SHA-256 (matching
  `RefreshTokenService`) — the raw token exists only in the emailed link and the single verify
  request; the database never holds a value an attacker with DB read access could replay directly.
- Single-use and 24h-expiring — a stale or already-clicked link fails closed.
- `/auth/register/verify` is unauthenticated by design (the token itself *is* the credential), same
  posture as `/auth/otp/verify`.

## Open Questions / Future Work

- **No rate limiting on `/auth/register/verify`** (unlike OTP's `maxAttempts`/hourly cap) — accepted
  because the token's entropy makes brute-forcing infeasible regardless of attempt count.
- **No audit trail** of verification link clicks, consistent with the rest of this app not having
  one yet (`admin-customers.md`, `transactional-email.md` flag the same gap elsewhere).

## Changelog

- 2026-08-27 — Implemented: `V12__create_registration_verification_tokens_table.sql`,
  `RegistrationVerificationToken`/`RegistrationVerificationTokenRepository`/
  `RegistrationVerificationService`, `RegistrationVerificationProperties` (24h default),
  `AppFrontendProperties`, `registration-verification.html` email template,
  `POST /auth/register/verify`, `AuthController` wiring. Unit-tested
  (`RegistrationVerificationServiceTest`, 7 tests). Full `mvn test` (80/80) passes.
- 2026-08-27 — Initial draft, written before implementation.

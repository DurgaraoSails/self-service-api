# JWT Authentication

## Status

In Progress

## Overview / Purpose

`self-service-api` previously only *validated* JWTs issued by an external IdP (Zitadel locally, Google Identity Platform in prod) via `spring.security.oauth2.resourceserver.jwt.issuer-uri`. There was no code, dependency, or config anywhere in the repo for *creating* tokens, and the `User` entity/repository/migration were empty stubs.

This feature makes `self-service-api` its own token issuer and validator: it signs JWTs with a self-held RSA private key (RS256), validates them with the matching public key, and supports a full access+refresh token lifecycle. The eventual login trigger will be an email+OTP flow (built as a separate, later service); this feature ships an interim path so tokens can be minted and the whole flow exercised end-to-end before OTP exists.

Zitadel and Google Identity Platform infra/config are left untouched — this feature only changes how `self-service-api` itself validates and issues tokens.

## Requirements

- Self-service-api issues and validates its own JWTs; no dependency on an external IdP for token validation.
- Asymmetric signing (RS256) with a private key never committed to source control.
- Claims must carry enough identity to authorize requests without a DB round-trip: user id, email, roles, tenant id.
- Refresh tokens must be revocable (logout, compromise) and rotated on use — not just long-lived stateless JWTs.
- A way to actually issue tokens today, despite the real OTP-based login not existing yet — without creating a permanent authentication bypass.
- **Registration**: a user supplying registration details who isn't already in the database gets registered (`PENDING_VERIFICATION`) and immediately sent an OTP; a user who already exists gets a `409` rather than being silently re-registered or logged in.
- **Login**: a user supplies only their email, receives an OTP, and on successful verification receives a JWT token pair — no separate "get tokens" step.

## Architecture Decisions

**Full replacement of external OIDC validation, not coexistence.**
`SecurityConfig` no longer points at an external `issuer-uri`/JWKS endpoint. Rejected running the two in parallel — this service is meant to own its own auth going forward, and dual validation paths add complexity without a clear need. `infra/zitadel/` remains in the repo untouched; it's just no longer wired into `SecurityConfig`.

**Signing: RS256 with a PEM key pair on disk, not HS256 and not a 3rd-party issuer.**
Asymmetric signing means the public key can be shared safely if other services ever need to validate these tokens, without exposing signing capability. A 3rd-party issuer (Auth0/Cognito/Firebase free tiers, etc.) was considered and rejected: it reintroduces an external dependency (contradicting "full replacement" above), most free tiers cap usage, and none allow embedding custom claims (roles, tenant) as freely as self-signing. Keys are generated via `scripts/generate-jwt-keys.sh` into `secrets/jwt/` (gitignored), loaded via configurable file paths — in prod this path will point at a Google Secret Manager-mounted file, consistent with how the README already describes secrets being handled.

**Library: JJWT (`io.jsonwebtoken`) with `jjwt-gson`, not `jjwt-jackson`.**
JJWT is the standard pairing for Spring Boot token *creation* (Nimbus, already on the classpath via the resource-server starter, only handles the validation side and is lower-level). `jjwt-jackson` would pull in a legacy Jackson 2 `jackson-databind` engine alongside the app's Jackson 3 HTTP stack purely for JWT claims (de)serialization — `jjwt-gson` avoids that entirely with zero interaction with the app's JSON stack.

**Validation: static public-key `NimbusJwtDecoder`, not a local JWKS endpoint.**
Since this single service is both issuer and validator, a live `/.well-known/jwks.json` endpoint would be unneeded machinery. The public key is loaded directly into a `NimbusJwtDecoder.withPublicKey(...)` bean, with `JwtValidators.createDefaultWithIssuer(...)` enforcing `iss` — a pure claim check, no network call. Revisit if/when other services need to validate these tokens independently.

**Login trigger: interim email-only endpoint, isolated behind a clean seam, gated out of prod.**
The real login flow (email + OTP) is a separate future service. Rather than block this feature on that work, `POST /auth/tokens` accepts just an email and treats it as "identity already verified" — but is disabled under the `prod` Spring profile, so it cannot act as an authentication bypass in a real deployment. Implementation note: `AuthController` implements the single generated `AuthApi` interface (all three `/auth/*` operations, same pattern as `UserController`/`UserApi`), so the gate is an `Environment.matchesProfiles("prod")` check at the top of the `issueTokens` method (returns 404) rather than a bean-level `@Profile` on the whole controller — a bean-level profile would have required splitting `refreshToken`/`logout` into a second controller, which isn't worth the duplication for a single guarded operation. The verify-then-issue seam is isolated in `AuthService.issueTokensForVerifiedEmail(email)`, so the only thing that needs to change when OTP ships is which controller calls that method.

**Registration creates users; OTP never does.**
Superseded: the original plan auto-created a `User` on first token issuance ("first login is signup"). Once merged with the registration feature (rich required fields: firstName/lastName/companyName), that no longer holds — a `User` can only be created via `POST /auth/register`, which throws `409 USER_ALREADY_EXISTS` if the email is already taken. `UserService.getActiveByEmail`/`getEligibleForOtpByEmail` only ever look up existing users; neither creates one.

**OTP serves two purposes, gated by status, not two separate mechanisms.**
The same `/auth/otp/{request,resend,verify}` endpoints handle both "verify my email to activate my new account" (user is `PENDING_VERIFICATION`) and "send me a login code" (user is `ACTIVE`) — there's no reason to duplicate the rate-limiting/hashing/lockout machinery for what's mechanically the same operation. `UserService.getEligibleForOtpByEmail` allows `PENDING_VERIFICATION` and `ACTIVE`, and rejects `INACTIVE`/`SUSPENDED`. On successful verification, `OtpService.verifyOtp` promotes a `PENDING_VERIFICATION` user to `ACTIVE` (setting `emailVerifiedAt`) before the caller issues tokens — so by the time `AuthService.issueTokensForVerifiedEmail` (which still requires strictly `ACTIVE`, via `getActiveByEmail`) runs, the promotion has already happened, for both the registration-verification and login cases.

**`AuthController.verifyOtp` returns `TokenResponse`, not `{verified, userId}`.**
A breaking change to an endpoint from the just-merged OTP branch, made deliberately: the end goal is "OTP verified → JWT," and returning `{verified, userId}` just to make the client immediately call another endpoint for tokens adds a round trip with no benefit. `OtpVerifyResponse` is removed from the schema now that nothing returns it.

**Registration auto-sends the first OTP.**
`POST /auth/register` creates the user and calls `OtpService.requestOtp` in the same request, returning the same `OtpRequestResponse` shape the standalone request endpoint returns — from the client's perspective, registering and getting your first code is one action, not two.

**User primary key: ULID string, not UUID.**
The OpenAPI spec's existing `UserResponse.id` example (`"01JABC123XYZ"`) is a ULID, not a UUID — matching that format keeps the implementation consistent with the contract that was already documented before this feature existed.

**Roles: native Postgres `text[]`, not a join table.**
Matches the OpenAPI `roles: string[]` shape 1:1. A join table would add relational overhead (a `roles` and `user_roles` table) for no benefit at this stage — roles here are simple string tags, not first-class entities with their own attributes.

**Refresh tokens: DB-backed opaque tokens with rotation and reuse detection, not stateless JWTs.**
A stateless refresh JWT can't be revoked before it expires — unacceptable given the explicit requirement for logout/compromise revocation. Instead: the refresh token handed to the client is a random opaque value (32 bytes via `SecureRandom`, base64url-encoded); only its SHA-256 hash is stored server-side. Every use rotates it (old token marked revoked, pointing at its replacement) and detects reuse: presenting an already-rotated/revoked token is treated as a compromise signal and revokes the entire token family for that user. A `version` column adds optimistic locking to close the race where two concurrent `/auth/refresh` calls both read the same not-yet-rotated token.

**`/api/v1` prefix mismatch: left alone.**
`SecurityConfig` has a pre-existing `/api/v1/**` matcher that matches nothing today (no `context-path` is configured; `/users/me` actually resolves unprefixed). New `/auth/**` routes follow the same actually-unprefixed pattern rather than fixing this mismatch, since fixing it (adding a global `context-path`) would also re-prefix `/actuator/health` and other management endpoints — a wider blast radius than this feature should take on.

**OpenAPI schemas split by domain.**
While adding the Auth schemas, `openapi/components/schemas.yaml` was split into `openapi/components/schemas/{common,user,auth}.yaml` (referenced via `$ref`) rather than appending to one flat file. This isn't JWT-specific, but happened as part of this feature; keeping it here since it's the origin of the convention for future domains.

## Data Model

**`users`** (migration `V1__create_users_table.sql`, entity `user/entity/User.java` — merged with the registration feature's richer schema; see Changelog)
| column | type | notes |
|---|---|---|
| id | VARCHAR(36) PK | ULID string |
| first_name / last_name | VARCHAR(100) NOT NULL | registration fields |
| company_name | VARCHAR(200) NOT NULL | registration field |
| job_title / country | VARCHAR(100) | nullable |
| email | VARCHAR(255) UNIQUE NOT NULL | |
| display_name | VARCHAR(100) | nullable, settable via `PUT /users/me` |
| status | VARCHAR(30) DEFAULT 'PENDING_VERIFICATION' | PENDING_VERIFICATION / ACTIVE / INACTIVE / SUSPENDED |
| roles | TEXT[] | defaults to `{USER}` on registration |
| tenant_id | VARCHAR(100) | nullable, indexed |
| trial_start_date / trial_end_date / last_login_date / email_verified_at | TIMESTAMPTZ | nullable |
| first_login | BOOLEAN DEFAULT TRUE | |
| created_at / updated_at | TIMESTAMPTZ | |

**`otp_verifications`** (migration `V2__create_otp_verifications_table.sql`, from the OTP feature — not owned by this spec, referenced for context)

**`refresh_tokens`** (migration `V3__create_refresh_tokens_table.sql`, entity `auth/entity/RefreshToken.java`)
| column | type | notes |
|---|---|---|
| id | UUID PK | |
| user_id | TEXT | FK → users, cascade delete |
| token_hash | TEXT UNIQUE NOT NULL | SHA-256 hex of the opaque token |
| expires_at | TIMESTAMPTZ | |
| revoked_at | TIMESTAMPTZ | nullable |
| replaced_by_token_id | UUID | nullable, self-FK, set on rotation |
| version | BIGINT DEFAULT 0 | optimistic locking |
| created_at | TIMESTAMPTZ | |

## API Surface

All new endpoints are unprefixed (`/auth/...`), public (`security: []`, overriding the OpenAPI-global `bearerAuth`), tagged `Auth` (generates a single `AuthApi` interface).

- **`POST /auth/register`** — `RegisterRequest {firstName, lastName, companyName, jobTitle?, country?, email}` → `201` `OtpRequestResponse {message, expiresInSeconds}`. Creates a `PENDING_VERIFICATION` user and sends the first OTP. `409 USER_ALREADY_EXISTS` if the email is already registered.
- **`POST /auth/otp/request`** / **`/resend`** — (from the OTP feature) send/resend a login or verification code; now accepts both `PENDING_VERIFICATION` and `ACTIVE` users.
- **`POST /auth/otp/verify`** — `OtpVerifyRequest {email, code}` → `LoginResponse` (`TokenResponse` + `user: UserResponse`). Verifies the code; if the user was `PENDING_VERIFICATION`, activates the account first. Either way, issues a real JWT token pair together with the caller's profile — this is the endpoint both registration-completion and login converge on, and the portal frontend uses the embedded `user` to populate the dashboard without a follow-up `GET /users/me` call.
- **`POST /auth/tokens`** — `IssueTokenRequest {email}` → `TokenResponse`. **Non-prod only** (returns 404 under the `prod` profile). Interim stand-in for OTP-verified login, now largely superseded by `/auth/otp/verify` — kept for now as a quick manual-testing path.
- **`POST /auth/refresh`** — `RefreshTokenRequest {refreshToken}` → `TokenResponse`. Rotates the refresh token; available in all environments.
- **`POST /auth/logout`** — `LogoutRequest {refreshToken}` → `204`. Revokes the refresh token (idempotent); available in all environments.

`TokenResponse`: `{accessToken, refreshToken, tokenType: "Bearer", expiresIn}`.

`LoginResponse`: `TokenResponse` plus `user: UserResponse` (`{id, email, firstName, lastName, status, displayName?, roles?, trialStartDate?, trialEndDate?}` — `firstName`/`lastName` are the registration fields, always present; the trial dates are UTC ISO-8601 instants, mapped from the `User` entity's `Instant` columns via `UserResponseMapper`, shared with `GET/PUT /users/me`). Only `/auth/otp/verify` returns this wider shape; `/auth/tokens` and `/auth/refresh` keep returning plain `TokenResponse` since they're not the "just logged in, populate the dashboard" moment.

**JWT claims** (access token): `sub` (user id), `email`, `roles`, `tenantId` (when present), `trialEndDate` (when present, epoch seconds — added by the trial-access-enforcement feature, see `docs/specs/trial-access-enforcement.md`), `iss=self-service-api`, `iat`, `exp`, `jti`.

**Default TTLs**: access token 30 minutes, refresh token 7 days (both configurable via `jwt.access-token-ttl` / `jwt.refresh-token-ttl`).

## Security Considerations

- **`POST /auth/tokens` is a deliberate, temporary auth bypass** (any caller can mint valid tokens for any email, no verification). Mitigated by an `Environment.matchesProfiles("prod")` check in `AuthController.issueTokens` that returns 404 under the `prod` profile — the route stays registered, but no token can be minted through it in that profile. Must be replaced by real OTP-gated issuance before this app is used with real user data in any environment where trust matters.
- Refresh tokens are never stored in plaintext — only a SHA-256 hash. Reuse of a rotated/revoked token revokes the entire family, limiting the blast radius of a leaked refresh token.
- Private key path is env-var configurable specifically so prod can point at a Secret-Manager-mounted file rather than a path inside the repo/image.
- **`POST /auth/register` returning `409 USER_ALREADY_EXISTS` confirms whether an email is registered** (a standard account-enumeration tradeoff for this kind of endpoint). Not mitigated here; flagged for a future call on whether to mask this behind a generic response.

## Open Questions / Future Work

- **Retire the interim `POST /auth/tokens`**: now that `/auth/otp/verify` issues real tokens, the interim endpoint's only remaining purpose is quick manual testing without needing a real OTP round-trip. Consider removing it once the OTP flow is exercised end-to-end in a real environment.
- **Account enumeration via `POST /auth/register`**: see Security Considerations. Not addressed yet.
- **`/api/v1` prefix mismatch**: tracked as a separate, pre-existing issue — not fixed as part of this feature.
- **Dead stub DTOs** (`user/dto/UserResponse.java`, `UpdateUserRequest.java`): left in place, out of scope for this feature.

## Changelog

- 2026-08-24 — Added `firstName`/`lastName` (required, from the `User` entity's registration fields) to `UserResponse`, so the portal dashboard's "Welcome {firstName} {lastName}," greeting no longer has to derive a name from `displayName`/email fallbacks. Verified the generated `UserResponse(id, email, firstName, lastName, status)` constructor argument order against the actual generated source before wiring `UserResponseMapper` — a same-type (String/String/String/String) argument-order mistake there wouldn't have been caught by the compiler.
- 2026-08-24 — Added CORS configuration (`CorsProperties`, `SecurityConfig.cors(...)`, `cors.allowed-origins` / `CORS_ALLOWED_ORIGINS`, defaulting to `http://localhost:4200`) so the `self-service-portal` Angular app can call these endpoints cross-origin — previously there was no CORS config at all and every browser call was blocked. Also changed `POST /auth/otp/verify` to return the new `LoginResponse` (`TokenResponse` + `user`) instead of plain `TokenResponse`, and added `trialStartDate`/`trialEndDate` to `UserResponse`, so the portal can populate the dashboard immediately after login without a second `GET /users/me` call. Extracted the `User` → `UserResponse` mapping (previously duplicated inline in `UserController`) into `UserResponseMapper`, shared by `UserController` and the new `AuthController.toLoginResponse`. `AuthService.issueTokensForVerifiedEmail` now returns a `LoginResult(User, TokenPair)` record instead of bare `TokenPair` so callers have the `User` without a second lookup; `/auth/tokens` unwraps `.tokenPair()` and keeps returning plain `TokenResponse`, unaffected by this change. On the portal side: `Auth` now holds the logged-in `UserResponse` as its source of truth (`isAuthenticated` is derived from it, not a separate flag), and the dashboard renders `trialStartDate`/`trialEndDate` via Angular's `date` pipe, which converts the UTC ISO strings to the browser's local timezone automatically.
- 2026-08-21 — Added a conditional `trialEndDate` claim (epoch seconds) to the access token, as part of the trial-access-enforcement feature — see `docs/specs/trial-access-enforcement.md` for the full rationale (avoids a DB round-trip to check trial status on every request, same principle as the existing conditional `tenantId` claim).
- 2026-08-20 — Initial draft, written before implementation, capturing all decisions made in the planning discussion.
- 2026-08-20 — During implementation: split `openapi/components/schemas.yaml` into per-domain files (`schemas/common.yaml`, `schemas/user.yaml`, `schemas/auth.yaml`). Revised the interim-endpoint gating mechanism from a bean-level `@Profile("!prod")` to an `Environment.matchesProfiles("prod")` check inside `AuthController.issueTokens` (returns 404), since `AuthController` implements the single generated `AuthApi` interface covering all three `/auth/*` operations and splitting it into two beans purely to gate one operation wasn't worth the duplication.
- 2026-08-20 — Implemented the registration flow and wired OTP verification to real token issuance: `POST /auth/register` (`UserService.registerUser`, throws `UserAlreadyExistsException` → `409`), `UserService.getEligibleForOtpByEmail` (replaces `OtpService`'s direct use of `getActiveByEmail`, allowing `PENDING_VERIFICATION` alongside `ACTIVE`), `OtpService.verifyOtp` now promotes `PENDING_VERIFICATION` → `ACTIVE` + sets `emailVerifiedAt` before returning, and `AuthController.verifyOtp` now calls `AuthService.issueTokensForVerifiedEmail` and returns `TokenResponse` instead of the removed `OtpVerifyResponse`. Compiles clean (52 generated+hand-written sources, all 9 `/auth/*` + `/users/me` operations). Not yet re-verified live end-to-end against Postgres (local dev DB volume needs a reset since the users table schema changed again).
- 2026-08-20 — Rebased onto `SS-Package-Structure` (which merged in the separately-developed `feature/otp-generation` branch: email+OTP request/resend/verify, `OtpVerification`/`ApiException`/`EmailService`, richer `User` fields — firstName/lastName/companyName/jobTitle/country/trialStartDate/trialEndDate/lastLoginDate/firstLogin/emailVerifiedAt — and `PENDING_VERIFICATION` status). Reconciled both features: `AuthController` now implements all 6 `/auth/*` operations (3 OTP + 3 JWT). `UserService.findOrCreateByEmail` (auto-provisioning) was replaced with `UserService.getActiveByEmail` (lookup-only, requires an existing ACTIVE user) — auto-provisioning would have violated the OTP branch's NOT NULL registration fields anyway, and this now matches the precondition `OtpService.verifyOtp` already enforced, with the duplicate check extracted out of `OtpService` into the shared method. `UserNotFoundException`/`InvalidRefreshTokenException` now extend the OTP branch's `ApiException` so there's one error-handling pattern instead of two. `refresh_tokens` migration renumbered `V2`→`V3` since the OTP branch's `otp_verifications` migration already claimed `V2`. OTP's schemas (previously stuck in the flat `schemas.yaml`) moved into their own `schemas/otp.yaml`, consistent with the per-domain convention. `User.id` generation stayed ULID-based (not the OTP branch's raw UUID) to keep matching the documented API example. Not done: wiring `verifyOtp` to actually return real JWT tokens instead of `{verified, userId}` — that's a contract change to already-shipped code, left as an open question rather than assumed.

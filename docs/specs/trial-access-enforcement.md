# Trial Access Enforcement

## Status

In Progress

## Overview / Purpose

`User` already has nullable `trial_start_date`/`trial_end_date` columns (from the original users
migration) that no code reads or writes — they're unused scaffolding. This feature makes them real:
every new registration gets a 14-day trial, and once it ends, the user is blocked from accessing
the application's features.

This is deliberately **not** an email feature: the trial end date is used only to restrict access,
not to trigger a notification. (Compare `docs/specs/transactional-email.md`, which covers the real
`EmailService` implementation and the separate Contact Sales trigger — trial expiry was explicitly
ruled out as a third `EmailService` trigger.)

## Requirements

- New registrations get a 14-day trial: `trial_start_date` = registration time, `trial_end_date` =
  start + 14 days. Trial length must be configurable, not a hardcoded literal.
- Once `trial_end_date` has passed, authenticated requests to the application's features must be
  rejected with a clear `403` (not a generic/empty Spring Security default response).
- No trial-expiry email is sent — enforcement only.
- Enforcement should not require a database round-trip on every request, consistent with this
  app's existing JWT design principle (see `docs/specs/jwt-authentication.md`: "claims must carry
  enough identity to authorize requests without a DB round-trip").

## Architecture Decisions

**Trial end date carried as a JWT claim, not checked via a per-request DB lookup.**
Extends the existing pattern in `JwtService.issueAccessToken` (which already conditionally adds a
`tenantId` claim) with a conditional `trialEndDate` claim, encoded as epoch seconds (standard JWT
numeric-date convention, and directly readable via `Jwt.getClaimAsInstant(...)`). This avoids a DB
hit on every authenticated request, matching the JWT feature's stated design philosophy.

**Enforcement via a custom `AuthorizationManager`, composed with the existing authentication check —
not a bespoke filter.** `SecurityConfig.securityFilterChain`'s `anyRequest().authenticated()` is
replaced with
`anyRequest().access(AuthorizationManagers.allOf(AuthenticatedAuthorizationManager.authenticated(), trialAuthorizationManager))`.
Composing rather than replacing preserves "no/invalid token → 401" (owned by the authenticated-check
half of the composite) while adding "valid token, trial expired → 403" (owned by
`TrialAuthorizationManager`) — a standalone custom manager risked misclassifying unauthenticated
requests as 403 instead of 401, since `ExceptionTranslationFilter` decides 401-vs-403 based on the
authentication state, not which sub-check failed.

**Accepted staleness: enforcement lags by up to one token lifetime.**
Because the claim is baked into the JWT at issuance, a user whose trial expires mid-token-life keeps
access until that access token expires or is refreshed. `AuthService.refresh()` re-reads the user
from the DB and reissues the claim on every `/auth/refresh` call, so real-world staleness is bounded
by `min(30-minute access-token TTL, time to the client's next refresh)`. Accepted as reasonable for
a daily-granularity cutoff; an alternative (DB-checked-per-request) was rejected as contradicting
the JWT feature's explicit no-DB-round-trip design goal for comparatively little precision gain.

**`AccessDeniedHandler` returns the same `ErrorResponse` shape as everything else, not Spring
Security's default empty 403 body.** `TrialExpiredAccessDeniedHandler` writes
`{code: "TRIAL_EXPIRED", message, timestamp, path}` via the app's Jackson 3 `ObjectMapper`, so a
trial-expired denial looks like every other API error rather than an unstyled security-framework
default.

**All authenticated routes are gated, including `/users/me` — no carve-out.**
There's no existing precedent in this codebase for "always-accessible" vs. "feature" routes, and
today `/users/me` is the only other authenticated endpoint besides the new Contact Sales one. Gating
everything under the existing `anyRequest()` catch-all is the simplest reading of "block access to
features." If it later turns out expired-trial users should still be able to view their own account
status via `/users/me`, that's a cheap follow-up:
`requestMatchers("/users/me").access(AuthenticatedAuthorizationManager.authenticated())` placed
before the `anyRequest()` catch-all (Spring Security matchers are first-match-wins).

**Trial length is a configuration property (`trial.length-days`, default 14), not a hardcoded
constant.** Matches the existing `otp.*`/`jwt.*` `@ConfigurationProperties` record convention in
this codebase, and lets the trial length change without a code change.

## Data Model

No schema change. `users.trial_start_date`/`users.trial_end_date` (both `TIMESTAMPTZ`, nullable,
already present in `V1__create_users_table.sql`) go from unpopulated to set at registration.

## API Surface

No new endpoints. Existing authenticated endpoints (`GET`/`PUT /users/me`, and the new
`POST /support/contact-sales`) gain a possible `403 TRIAL_EXPIRED` response alongside their existing
responses.

**JWT claims (access token), addition:** `trialEndDate` (epoch seconds) — present only when
`user.trialEndDate` is non-null (mirrors the existing conditional `tenantId` claim).

## Security Considerations

- This is a genuine new authorization boundary: a previously-authenticated user can start receiving
  `403`s once their trial lapses. Verified via manual end-to-end testing (see verification plan in
  the implementation plan) since no automated test suite exists yet for this app's auth layer.
- No paid/upgrade path exists in this codebase — once a trial ends, there is currently no mechanism
  to restore access. Acceptable for the current product stage (no subscription/billing concept
  exists anywhere yet); flagged as a known limitation, not a gap introduced by this feature.
- `TrialExpiredAccessDeniedHandler` currently labels every `AccessDeniedException` as
  `TRIAL_EXPIRED`, since trial expiry is the only authorization-denial reason in the app today. If
  role-based or other authorization checks are added later, this handler will need to generalize
  (e.g. carry a reason on a custom `AuthorizationDecision` rather than assuming the cause).

## Open Questions / Future Work

- **Should `/users/me` be exempt from trial gating?** No existing precedent either way; current
  default is "no exemption" (see Architecture Decisions). Revisit if product wants expired-trial
  users to retain basic account visibility.
- **No upgrade/paid path.** Once trial access enforcement ships, there is no way for a user to
  regain access after their trial ends. This is a real product gap, not just a technical one — flag
  for whoever owns the pricing/subscription roadmap.
- **`AccessDeniedHandler` generality**, as noted in Security Considerations.

## Changelog

- 2026-08-21 — Implemented: `TrialProperties` (`trial.length-days`, default 14), `UserService.registerUser` now sets `trialStartDate`/`trialEndDate`, `JwtService.issueAccessToken` adds the conditional `trialEndDate` claim, `TrialAuthorizationManager` + `TrialExpiredAccessDeniedHandler` added and wired into `SecurityConfig` via `AuthorizationManagers.allOf(...)` and `exceptionHandling(...)`. `/users/me` and `/support/contact-sales` OpenAPI responses gained `403`. Unit-tested (`TrialAuthorizationManagerTest`: no-claim/future/past/non-JWT cases; `JwtServiceTest`: claim present/absent) — all pass. Full end-to-end verification (register → decode token → confirm claim → force-expire → confirm 403) was not run live in this session due to an unrelated local-environment limitation (embedded Tomcat can't open a loopback socket in the sandboxed shell used here); the Spring Security bean wiring itself was confirmed via a full, successful `ApplicationContext` refresh short of the final web-server-start step.
- 2026-08-21 — Initial draft, written before implementation.

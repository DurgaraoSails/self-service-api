# Admin Users

## Status

In Progress

## Overview / Purpose

Introduces an "admin" role. Admins can manage the POC catalog (create, edit, hide/unhide, soft-delete/restore) and see an "Activity" telemetry surface on the portal that regular users don't (the telemetry feature itself is specced separately in `docs/specs/activity-tracking.md`; this spec only covers the role and the write-access gating it enables). Every authenticated, non-expired-trial user currently has unrestricted POC write access — see `docs/specs/poc-catalog.md`'s "Admin-only writes" open question — this closes that gap.

## Requirements

- A user can be flagged as an admin.
- No admin-management UI in this round — admin status is bootstrapped from a config-driven list of email addresses.
- Admin-only backend enforcement for POC create/update/delete/hide/unhide/restore, returning a real "forbidden" error (not a misleading trial-expiry one) to non-admins.
- The frontend needs a reliable way to know if the current user is an admin, to gate UI (Add POC button, POC card admin controls, Activity nav tab).

## Architecture Decisions

**Admin bootstrap: a config-driven email allowlist, applied on every OTP verification.**
`AdminProperties` (`user/config/AdminProperties.java`) reads `admin.emails` (bound from the `ADMIN_EMAILS` env var, comma-separated) and exposes `isAdminEmail(String)`. The check runs inside `OtpService.verifyOtp`, immediately before the user is saved. This app has no password login — `/auth/otp/verify` is the only login path, called both at registration completion and on every subsequent login (see `AuthController.verifyOtp`'s own comment) — so this single insertion point covers both a newly-registering admin and an existing user who's added to the list later (they're promoted on their next login, no backfill needed). The alternative considered — a one-off migration setting a specific user's role — was rejected because it doesn't handle promoting a second admin without writing another migration, and doesn't handle a user who registers before being added to the list.

**No new `Role` enum or table — `roles` stays a free-form `text[]`, `"ADMIN"` is just a new value in it.**
`users.roles` already exists as a Postgres `text[]` (added for the JWT auth feature, see `jwt-authentication.md`), and `SecurityConfig`'s `JwtGrantedAuthoritiesConverter` already turns each entry into a `ROLE_<value>`-prefixed Spring Security authority. Adding `"ADMIN"` as a second array element needed zero schema or JWT-claim-shape changes — only a real reader (`@PreAuthorize`) and a real writer (the bootstrap check above) needed to be added.

**Enforcement: `@PreAuthorize("hasRole('ADMIN')")` on the concrete `PocController` methods, with `@EnableMethodSecurity` newly added to `SecurityConfig`.**
`SecurityConfig`'s existing `authorizeHttpRequests` matchers are URL/method-based and can't express "admins get a different response body than everyone else on the same `GET /pocs` URL" (needed for hidden/deleted POC visibility — see `poc-catalog.md`). Method-level security composes cleanly with that per-request filtering, which still has to live in the service/controller layer regardless. Annotations went on `PocController`'s methods, not the generated `PocApi` interface, per standard Spring Security guidance for proxy-based enforcement (and because the interface is generated, unreviewable, code).

**Fixed `TrialExpiredAccessDeniedHandler` to stop misreporting every denial as a trial expiry.**
Before this change, the app had exactly one authorization-denial reason (`TrialAuthorizationManager`), and `TrialExpiredAccessDeniedHandler`'s own doc comment said so explicitly — it unconditionally rendered `TRIAL_EXPIRED` for any `AccessDeniedException`. Adding `@PreAuthorize` introduces a second denial reason (not being an admin), which would otherwise surface through the same handler with the same misleading message. The handler now re-derives the trial-expiry check itself (mirroring `TrialAuthorizationManager`'s own `trialEndDate` claim comparison) and only returns `TRIAL_EXPIRED` when that's actually true, falling back to a generic `ACCESS_DENIED` otherwise.

**Frontend: `Auth.isAdmin` computed signal, an `adminGuard` mirroring `authGuard`.**
`UserResponse.roles` was already plumbed through end-to-end on the frontend (added speculatively during the JWT work, never consumed) — `isAdmin` just reads it. `adminGuard` follows `authGuard`'s exact `CanActivateFn` shape: redirect unauthenticated users to `/login`, redirect authenticated non-admins to `/dashboard`, otherwise allow.

## Data Model

No new tables. `users.roles` (`TEXT[]`, from `V1__create_users_table.sql`) gains a second possible value, `"ADMIN"`, alongside the existing `"USER"` — no migration needed.

## API Surface

No new endpoints from this spec directly (see `poc-catalog.md` for the hide/unhide/restore endpoints this role gating unlocks). Behavioral change to existing endpoints:

- `POST /pocs`, `PUT /pocs/{id}`, `DELETE /pocs/{id}`, and the new `POST /pocs/{id}/hide|unhide|restore` (see `poc-catalog.md`) now require the `ADMIN` role, not just authentication. Non-admins get `403` with body `{ "code": "ACCESS_DENIED", ... }`.
- `GET /pocs` response contents now depend on caller role (see `poc-catalog.md`'s Data Model / API Surface updates) — no contract shape change, just which rows are included.

## Security Considerations

- The `ADMIN_EMAILS` allowlist is plain configuration (env var), not a secret in the sense of needing encryption — but it's operationally sensitive (controls who gets write access to the POC catalog) and should be set via the same deployment-secrets mechanism as other env vars (`secrets/README.md`), not committed to `application.yaml` with real values.
- A user removed from `ADMIN_EMAILS` is **not** automatically demoted — the bootstrap check only ever adds the role, never removes it, and there's no re-verification of the allowlist against already-assigned roles on each request. Demoting an existing admin currently requires a direct database update. Flagged under Open Questions.
- The dev-only `/auth/tokens` interim-login endpoint bypasses `OtpService.verifyOtp` entirely (disabled under the `prod` profile), so it never grants the `ADMIN` role — a locally-tested user relying on that endpoint won't see the promotion until they log in via real OTP verification.

## Open Questions / Future Work

- **No demotion path**: removing an email from `ADMIN_EMAILS` doesn't revoke an already-granted `ADMIN` role. An admin-management UI (explicitly deferred this round) or a startup reconciliation job would be needed to close this.
- **No admin-management UI**: promoting/demoting admins is entirely config-driven for now; a future round could add a real UI for an existing admin to manage others.

## Changelog

- 2026-08-26 — Initial draft: config-driven admin bootstrap via `OtpService.verifyOtp`, `@EnableMethodSecurity` + `@PreAuthorize` on POC write endpoints, `TrialExpiredAccessDeniedHandler` fix, frontend `Auth.isAdmin` + `adminGuard`.

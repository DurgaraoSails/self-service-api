# Admin Customer Management

## Status

In Progress

## Overview / Purpose

Gives admins a "Customers" view of every registered user, with the ability to revoke a user's trial
immediately or extend it to a new end date. This is the first mechanism in the codebase for restoring
access after a trial ends — `docs/specs/trial-access-enforcement.md` flagged "no upgrade/paid path...
once a trial ends, there is currently no mechanism to restore access" as an open gap; the extend
action resolves that (informally, not as a billing/subscription feature — an admin now has a manual
lever).

## Requirements

- Admins can see all registered users, paginated (default page size 30).
- The list is filterable by registration date: last week, last month (default), last year, or a
  custom range.
- Admins can revoke a user's trial (ends it immediately) or extend it to a new date, picked via a
  date picker capped at 14 days from today.
- A badge shows the count of users whose trial ends within the next day (today or tomorrow), so
  admins notice before those users lose access.
- Clicking that badge filters straight to those users.

## Architecture Decisions

**Reuses the existing `User` entity and `user` package — no new domain, no new table.**
Every field needed (`companyName`, `jobTitle`, `country`, `status`, `roles`, `trialStartDate`,
`trialEndDate`, `createdAt`) already exists on `User`; `createdAt` (set once at registration,
`@PrePersist`, never updated) doubles as "registered date" for the filter — there's no separate
"registration date" concept to add.

**New endpoints join the existing `User` tag/`UserApi`, not a separate "Admin" API.** Mirrors the
POC catalog's precedent (`docs/specs/poc-catalog.md`): the same resource's self-service and
admin-management operations live together (`GET/PUT /users/me` next to the new `GET /users`,
`POST /users/{id}/trial/revoke`, `POST /users/{id}/trial/extend`), all admin-gated via
`@PreAuthorize("hasRole('ADMIN')")` (see `docs/specs/admin-users.md`).

**Explicit `CustomerPageResponse` schema instead of Spring's `Page<T>`/`Pageable` machinery.**
This codebase has no `springdoc-openapi` integration for auto-documenting `Pageable` query params,
and every other response in the contract is a hand-composed schema. A plain
`{content, page, size, totalElements, totalPages}` object, built from Spring Data's internal
`Page<User>` in the controller, keeps the wire contract explicit and consistent with the rest of the
API rather than leaking a framework-specific shape.

**Revoke and extend are dedicated action endpoints, not a `PUT`.** Same reasoning as the POC
hide/unhide/restore endpoints: single-purpose actions on two fields (`trialEndDate`, implicitly
`trialStartDate` is untouched) shouldn't be expressed as a full-resource replace.

**Extend validates against "now + 14 days," not the user's current `trialEndDate`.** A flat,
calendar-relative cap (`trialEndDate <= now + 14 days`) is simpler to reason about and validate than
a per-user relative cap, and matches how the requirement was phrased ("cannot extend the trial more
than 14 days from current date"). Also rejects a `trialEndDate` in the past — extending to a past
date would just be a confusing way to revoke.

**"Needs attention" (badge + filter) is one server-side predicate, not duplicated client-side
logic.** `status = ACTIVE AND trialEndDate BETWEEN now AND now+1day` is computed once, in
`UserService`, and used by both `GET /users/trial-alerts/count` (badge count) and
`GET /users?needsAttention=true` (the filtered list the badge links to) — keeping the "what counts as
expiring soon" rule in exactly one place. When `needsAttention=true` is set, the registration-date
filters are ignored (the admin wants everyone needing action, regardless of when they signed up).

**Same JWT-staleness caveat as trial enforcement itself, symmetrically.**
`docs/specs/trial-access-enforcement.md` already accepts that a trial-expiry check can lag up to one
access-token lifetime, since `trialEndDate` is baked into the JWT at issuance. Revoking or extending
a trial here changes the `users` row immediately, but the *affected user's own session* won't see the
new `trialEndDate` until their token is refreshed. This is the same accepted tradeoff, not a new gap.

## Data Model

No schema change. Reads/writes only `users.trial_start_date`/`users.trial_end_date` (already
present), `users.created_at` (read-only, for the registration-date filter).

## API Surface

All new endpoints are admin-only (`@PreAuthorize("hasRole('ADMIN')")`), under the `User` tag.

- **`GET /users`** — `registeredFrom?`, `registeredTo?` (date-time), `needsAttention?` (boolean,
  default `false`), `page?` (default `0`), `size?` (default `30`). When `needsAttention=true`, the
  registration-date params are ignored and the result is every `ACTIVE` user whose `trialEndDate`
  falls within the next day. Otherwise, results are optionally bounded by `registeredFrom`/`registeredTo`
  (either or both may be omitted — an omitted bound is unbounded on that side). Sorted by `createdAt`
  descending. Returns `CustomerPageResponse`.
- **`POST /users/{id}/trial/revoke`** — sets `trialEndDate = now`. `200` `CustomerResponse`. `404` if
  not found.
- **`POST /users/{id}/trial/extend`** — body `ExtendTrialRequest {trialEndDate}`. `400` if
  `trialEndDate` is in the past or more than 14 days from now. `200` `CustomerResponse`. `404` if not
  found.
- **`GET /users/trial-alerts/count`** — `200` `TrialAlertsCountResponse {count}`, the same
  `needsAttention` predicate as `GET /users`, uncounted by pagination.

## Security Considerations

- All four endpoints require the `ADMIN` role — a non-admin gets `403 ACCESS_DENIED` (see
  `docs/specs/admin-users.md` for the shared enforcement mechanism and the `TrialExpiredAccessDeniedHandler`
  fix that makes this distinguishable from an actual trial-expiry denial).
- `GET /users` returns full profile data (email, company, job title, country) for every registered
  user to any admin — acceptable since admin is already a fully-trusted role in this app (no
  further-scoped "read-only support" role exists), consistent with POC management's all-or-nothing
  admin trust model.

## Open Questions / Future Work

- **No audit trail.** Revoke/extend actions aren't logged anywhere beyond the row's own `updated_at`
  timestamp — if two admins exist, there's no record of *who* revoked or extended a given user's
  trial. Worth adding if a second admin is ever onboarded.
- **No email on revoke/extend.** Matches `trial-access-enforcement.md`'s existing "not an email
  feature" stance — the user finds out only when they next hit a blocked feature (revoke) or simply
  keeps working (extend). Revisit together if trial-expiry notifications are ever added.

## Changelog

- 2026-08-26 — Initial draft, written before implementation.

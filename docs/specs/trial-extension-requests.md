# Trial Extension Requests

## Status

Implemented

## Overview / Purpose

The trial-expired modal (`self-service-portal`'s `TrialExpiredModal`) has always shown "Extend" and
"Contact Sales" buttons, but neither has ever called the backend — clicking either just swaps the
modal to a static "thanks" message. `POST /support/contact-sales` already exists and works (see
`docs/specs/transactional-email.md`) but the modal never calls it. "Extend" has no backend
counterpart at all — this is why an admin sees nothing when a user clicks it.

This feature: (1) wires the modal's Contact Sales button to the real endpoint, and (2) adds a real
trial-extension-request flow — the user is asked why they want more time (a required note), which
emails the sales inbox (mirroring Contact Sales) and is persisted on their `User` row so it's visible
to an admin, both via the existing "needs attention" badge/filter (`docs/specs/admin-customers.md`)
and a new read-only note affordance on the Customers list.

## Requirements

- Clicking "Extend" in the trial-expired modal prompts for a required, free-text reason before
  submitting.
- Submitting emails the sales inbox (same recipient as Contact Sales) with the user's profile and
  their reason.
- The request (note + when) is saved on the user so an admin can review it later, not only via a
  one-time email.
- A user with a pending extension request counts toward the existing Customers "needs attention"
  badge/filter, alongside users whose trial is ending soon.
- The Customers list shows a read-only note indicator for any customer with a pending request;
  clicking it reveals the note. Not editable from there.
- An admin resolving the request — extending or revoking the trial — clears the pending state.
- Clicking "Contact Sales" in the same modal actually calls `POST /support/contact-sales` (previously
  a no-op).

## Architecture Decisions

**Two plain nullable columns on `users`, not a separate requests table.** Mirrors how
`trialStartDate`/`trialEndDate` already live directly on `User` rather than a separate "trial"
entity. One open request per user at a time is the only case that matters here — there is no need to
keep a history of past requests, and a full table would need its own lifecycle (superseding old rows,
etc.) for no real benefit yet.

**Reuses the Contact Sales email pattern (`SupportService`/`support.sales-email`/Thymeleaf template),
not a new notification channel.** `docs/specs/transactional-email.md` established this exact pattern
for "an authenticated user triggers an email to sales about themselves." A trial-extension request is
the same shape (profile + free text), just a different subject/template — adding a second, parallel
notification mechanism (e.g. an in-app-only signal with no email) would leave the same "nobody
actually gets pinged" gap this feature exists to close.

**Also feeds the existing `needsAttention` predicate, not a separate badge.**
`docs/specs/admin-customers.md` already established a single "needs attention" predicate/badge for
"users an admin should look at." A pending extension request is exactly that kind of thing — OR'd into
the existing predicate (`UserSpecifications.needsAttention`) rather than adding a second badge/filter
the admin would have to separately remember to check.

**Endpoint lives on `UserApi`/`UserController` (`POST /users/me/trial/extension-request`), not
`SupportApi`.** The URL is user-resource-scoped and the primary write is on the `User` row; the email
step is a side effect of that write, not the other way around (contrast Contact Sales, which is
*purely* an email with no persisted state, and so is scoped separately under `/support`).
`UserController` calls `UserService` (persist) and then `SupportService` (notify) — new
`SupportService.notifyTrialExtensionRequest(userId, note)` method, mirroring `contactSales`.

**Note is required, capped at 2000 characters (same cap as Contact Sales' optional message).** The
entire point of asking is to capture a reason; an empty note defeats that. 2000 chars matches the
existing precedent (`ContactSalesRequest.message`) rather than inventing a new limit.

**204 No Content response, no body.** The frontend modal doesn't need the updated user back — it just
moves to a static confirmation message, the same shape as `/activity/heartbeat`'s fire-and-forget
response.

**Resolving via the existing revoke/extend actions clears the pending state.** Both
`UserService.revokeTrial` and `extendTrial` now null out `pendingExtensionNote`/
`pendingExtensionRequestedAt` — an admin who's already acted on the request (in either direction)
shouldn't keep seeing it as unresolved. There is no separate "dismiss" action.

**No new frontend `SupportApi` service existed — added one.** The backend endpoint has been live and
untouched since `transactional-email.md` shipped; `self-service-portal` never built a client for it.
Added `core/support/support-api.ts` (one `contactSales(message?)` method) rather than folding it into
`UserApi`, matching the backend's own `Support` vs `User` tag split.

## Data Model

`users` table (`V11__add_pending_trial_extension_to_users_table.sql`):
- `pending_extension_note TEXT` — nullable.
- `pending_extension_requested_at TIMESTAMPTZ` — nullable. Non-null is exactly "there is a pending
  request."

## API Surface

- **`POST /users/me/trial/extension-request`** — body `RequestTrialExtensionRequest {note}` (required,
  1–2000 chars). Sets `pendingExtensionNote`/`pendingExtensionRequestedAt` on the caller, emails
  `support.sales-email`. `204` No Content. Authenticated (any user, no admin role required — inherits
  the global `bearerAuth` requirement like `/users/me`).
- **`CustomerResponse`** gains `pendingExtensionNote?` and `pendingExtensionRequestedAt?`, so the
  existing `GET /users` (Customers list) exposes the pending request without a new endpoint.
- **`GET /users?needsAttention=true`** / **`GET /users/trial-alerts/count`** — predicate now also
  matches `pendingExtensionRequestedAt IS NOT NULL`, in addition to the existing trial-ending-soon
  condition.
- No change to `POST /users/{id}/trial/revoke` / `.../trial/extend`'s request/response shapes; both
  now additionally clear the pending-extension fields as a side effect.

## Security Considerations

- Same posture as Contact Sales: authenticated, self-only (acts on the caller's own JWT subject, not
  an arbitrary user id) — a user can only request an extension for themselves.
- The note is free text, rendered into an HTML email via the same Thymeleaf `th:text` escaping already
  used for Contact Sales' `message` field (auto-escaped, not `th:utext`) — no injection risk beyond
  what already exists for that field.

## Open Questions / Future Work

- **No rate limiting**, same as Contact Sales — an authenticated user could resubmit repeatedly (each
  call just overwrites the note/timestamp, no accumulation).
- **No history.** Only the latest pending request is kept; once resolved (or overwritten by a new
  request), the previous note/timestamp is gone. Acceptable for now since there's no other request
  history/audit trail in the app either (`admin-customers.md` flags the same gap for revoke/extend).

## Changelog

- 2026-08-27 — Implemented: `V11__add_pending_trial_extension_to_users_table.sql`, `User` entity
  fields, `UserSpecifications.needsAttention` OR'd with the pending-request condition,
  `UserService.requestTrialExtension`/`revokeTrial`/`extendTrial` (clearing on resolve),
  `UserRepository.countByStatusAndTrialEndDateBetween` replaced by a spec-based count so it can't
  drift from the list predicate, `SupportService.notifyTrialExtensionRequest` +
  `trial-extension-request.html` template, `POST /users/me/trial/extension-request`, `CustomerResponse`
  fields, `UserController` wiring. Portal: `core/support/support-api.ts` (new — Contact Sales had no
  frontend client at all), `TrialExpiredModal` reworked into a real Extend-with-reason flow plus a
  working Contact Sales call, new read-only `NoteViewerModal` and a note icon on the Customers list.
  Backend `mvn test` (73/73) and frontend `ng test` (215/215) both pass. Live SMTP send and full
  end-to-end HTTP verification were not exercised in this session — same pre-existing local
  loopback-socket sandbox limitation noted in `transactional-email.md`.
- 2026-08-27 — Initial draft, written before implementation.

# Activity Tracking

## Status

In Progress

## Overview / Purpose

Admins need to see how much time users spend in each POC. `docs/specs/admin-users.md` shipped the
`ADMIN` role and an admin-only `/activity` route on the portal, but deliberately left the telemetry
itself out ("the telemetry feature itself is being built separately") — the route pointed at an
empty placeholder component. This feature fills that in end to end:

- A per-POC leaderboard, most-used first, so it's obvious which POCs are earning their keep.
- A per-user list of total time spent.
- A drill-down into one user: their per-POC breakdown plus a session-level timeline.

## Requirements

- Record how long each user spends in each POC, per POC and in total.
- Admins can see POCs ranked by total usage across all users, highest first.
- Admins can see every user's total usage, and drill into one user's per-POC breakdown.
- A regular user can record their own usage but must not be able to read anyone's activity.
- Idle/backgrounded browser tabs must not inflate usage numbers.

## Architecture Decisions

**Client-driven heartbeats, because the server cannot see into the POC.**
A POC is a third-party app embedded in an `<iframe>` on the portal's workspace page. The backend
has no visibility into it whatsoever — no requests flow through this API while a user works inside
a POC, so there is nothing server-side to measure. The portal therefore pings
`POST /activity/heartbeat` on an interval while a workspace is open, and the backend derives
duration from the gaps between pings. The client never reports an elapsed time itself; it only
says "I am still here", and the server does the arithmetic. That keeps a tampered or buggy client
from inventing usage — the worst it can do is keep pinging, which is bounded by wall-clock time.

**One row per session, not per heartbeat tick.**
A tick-per-row table would grow at (users x POCs x 3/minute) forever to answer questions that are
all aggregates anyway. Instead each row is a continuous stretch of use (`started_at`,
`last_seen_at`, `ended_at`, `total_seconds`), extended in place by each heartbeat. This still
supports the session timeline the drill-down needs, at a tiny fraction of the row count.

**Sessions close implicitly, via a grace period — no "end session" call, no cleanup job.**
Relying on the client to announce that it's leaving would mean a closed laptop, a crashed tab, or
a lost network connection leaves a session open forever, accruing bogus time. Instead each
heartbeat compares `now` against `last_seen_at`:
- within `GRACE_PERIOD` (90s) → the same session is extended by the elapsed gap;
- beyond it → the old session is closed *at its own `last_seen_at`* (the last moment we actually
  know the user was there — not `now`, which would silently bank the entire absence as usage),
  and a fresh session begins.

So an abandoned tab simply stops accumulating on its own, and a user returning to the same POC
hours later gets a second session rather than one session with a bogus multi-hour middle. This is
why no reconciliation job exists: there is no such thing as a stale session needing repair, only
one that hasn't been observed as closed yet.

**A `MAX_TICK_SECONDS` (120s) cap on what any single heartbeat can credit.**
Without it, a laptop suspended for 89 seconds — still inside the grace period — would bank the
whole gap on the next ping. The cap is deliberately larger than the client's 20s interval so
normal operation is never clipped; it only bites on clock jumps and suspends.

Both constants live in `ActivityService`, not a `@ConfigurationProperties` class. They're
internal to the algorithm and nobody has asked to tune them per environment; a config class would
be speculative surface area.

**Heartbeat authorship comes from the JWT subject, never the request body.**
`HeartbeatRequest` carries only `pocId`. Accepting a `userId` would let any authenticated user
attribute usage to someone else.

**Aggregation in the database, via JPQL ad-hoc entity joins.**
`ActivitySession` stores `userId`/`pocId` as plain columns rather than `@ManyToOne` associations
(consistent with how the rest of this codebase treats cross-domain references — `Poc` and `User`
are separate aggregates). The leaderboard queries therefore join explicitly
(`FROM ActivitySession s JOIN Poc p ON p.id = s.pocId`) and return interface projections, so
`SUM`/`GROUP BY`/`ORDER BY` all run in Postgres instead of pulling every session into memory to
total up in Java. POC and user display names are denormalised into the response so the portal
doesn't need a second round-trip to render a leaderboard row.

**Frontend: the heartbeat timer is owned by the `id` effect, not `ngOnDestroy`.**
`/poc/:id/workspace` is a single route config, so Angular's default `RouteReuseStrategy` *reuses*
the `PocWorkspace` instance when the user goes straight from one POC's workspace to another —
only the `id` input signal changes, and `ngOnDestroy` never fires. A timer torn down in
`ngOnDestroy` would keep crediting the previous POC while the user is looking at the next one.
Starting and clearing the interval inside the same `effect()` that reacts to `id()`, via its
cleanup callback, is what makes POC-to-POC navigation correct. This is covered by a regression
test in `poc-workspace.spec.ts`.

**Heartbeats pause on a hidden tab.**
The interval checks `document.visibilityState` before firing, so a POC workspace left open in a
background tab stops counting. Combined with the grace period, a user who tabs away for a few
minutes gets their session closed at their last real activity rather than credited for the whole
detour.

## Data Model

**`activity_sessions`** (migration `V9__create_activity_sessions_table.sql`, entity
`activity/entity/ActivitySession.java`)

| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| user_id | VARCHAR(36) NOT NULL | FK → users, cascade delete |
| poc_id | BIGINT NOT NULL | FK → pocs (no cascade — POCs are soft-deleted, so history survives) |
| started_at | TIMESTAMPTZ NOT NULL | first heartbeat of this session |
| last_seen_at | TIMESTAMPTZ NOT NULL | most recent heartbeat |
| ended_at | TIMESTAMPTZ | null while open; set to `last_seen_at` when closed |
| total_seconds | BIGINT NOT NULL DEFAULT 0 | only ever accumulates confirmed, capped gaps |
| created_at / updated_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |

Indexed on `user_id` and `poc_id` — every query filters or groups by one of them.

## API Surface

New endpoints under `/activity`, tagged `Activity` (generates `ActivityApi`). All authenticated;
the three read endpoints are additionally `@PreAuthorize("hasRole('ADMIN')")` on the concrete
`ActivityController` methods, matching `PocController`'s convention. No new `SecurityConfig`
matcher was needed — these fall under the existing authenticated-by-default catch-all.

- **`POST /activity/heartbeat`** — `HeartbeatRequest {pocId}` → `204`. Any authenticated user,
  recording their own usage only. `404 POC_NOT_FOUND` for an unknown POC.
- **`GET /activity/leaderboard/pocs`** — **admin only**. `PocUsageSummary[]`
  (`{pocId, pocName, totalSeconds, userCount}`), ordered by `totalSeconds` desc.
- **`GET /activity/leaderboard/users`** — **admin only**. `UserUsageSummary[]`
  (`{userId, firstName, lastName, email, totalSeconds}`), ordered by `totalSeconds` desc.
- **`GET /activity/users/{userId}`** — **admin only**. `UserActivityDetail` (`allOf` over
  `UserUsageSummary` + `byPoc: PocUsageSummary[]` + `sessions: ActivitySessionResponse[]`).
  `404 USER_NOT_FOUND`.

`PocUsageSummary.userCount` is optional and deliberately left unset inside `byPoc` — scoped to a
single user it would always be `1`, which reads as data rather than a constant.

Durations cross the wire as raw integer seconds; the portal formats them
(`core/activity/format-duration.ts`), matching how trial dates are sent as raw instants and
formatted client-side.

## Security Considerations

- The heartbeat endpoint records usage against the bearer token's subject only — a caller cannot
  attribute usage to another user, nor inflate it beyond real elapsed wall-clock time (each ping
  credits at most the real gap, capped at `MAX_TICK_SECONDS`).
- All three read endpoints are admin-gated; a regular user can generate their own activity but
  cannot read their own or anyone else's aggregates. Verified end to end: a non-admin token gets
  `204` on heartbeat and `403 ACCESS_DENIED` on all three reads.
- Activity data is personally identifying behaviour (who used what, when). It's exposed only to
  admins, and there's no retention policy or purge path yet — see Open Questions.
- `CurrentUser.id()` was added alongside the existing `isAdmin()`, replacing what would have been
  a third ad-hoc copy of the `Jwt`-principal cast (`SupportController` has the other).

## Open Questions / Future Work

- **No retention/purge policy.** Sessions accumulate indefinitely. Fine at current volume; revisit
  before this is pointed at a real user base, both for table size and for how long behavioural
  data should be kept.
- **No date-range filtering on the leaderboards/drill-down.** `GET /activity/usage/daily` now
  covers the trend chart (any `[from, to]`, e.g. week/month/year/custom), but the POC and user
  leaderboards, and a single user's session list, remain all-time. Scoping those to a range too
  would need the same `from`/`to` params threaded through `findPocUsage` / `findUserUsage` /
  `findSessionsByUserId`.
- **Deleted POCs still appear** in the leaderboard if they have recorded usage — the join doesn't
  filter on `deleted_at`. Arguably correct (the time really was spent), but worth an explicit
  product call.
- **Best-effort final heartbeat on tab close** was designed but not implemented: `sendBeacon`
  can't carry the `Authorization` header, so it would need `fetch(..., {keepalive: true})` in a
  `pagehide` listener. Skipped because the grace-period design already bounds the loss to under
  one heartbeat interval.
- **Concurrent sessions for the same user+POC** (two tabs on the same POC) will extend a single
  session rather than counting double — which is the desired behaviour — but two tabs on
  *different* POCs will legitimately accrue time in parallel, so a user's total can exceed their
  real wall-clock time. Not mitigated; flagged in case the numbers ever look surprising.

## Changelog

- 2026-08-27 — Added `GET /activity/usage/daily` (daily usage totals over an admin-supplied
  `[from, to]` date range, zero-filled via a `generate_series` LEFT JOIN so every day in range
  produces a point) and `GET /activity/active` (sessions currently within the grace period,
  system-wide) to back the redesigned admin Activity page's trend chart and live-status view.
  Added `idx_activity_sessions_started_at` (migration `V10`) since the daily-usage query filters
  and buckets on `started_at`. Both bucketing (UTC calendar day, explicitly `AT TIME ZONE 'UTC'`
  then cast `::date`) and the generated series were verified against a real disposable Postgres
  instance, not just unit tests with a mocked repository — `generate_series` has no `(date, date,
  interval)` overload, so Postgres resolves the cast `date` inputs to the `timestamptz` overload;
  left uncast, the `day` column comes back as `timestamptz`, which risks an off-by-one-day shift
  once JDBC maps it to `LocalDate` depending on JVM default timezone. Both the series column and
  the join's right-hand side are explicitly cast `::date` to close that off.
- 2026-08-27 — `ActivitySessionResponse` gained a computed `status` (`ACTIVE`/`ENDED`), because a
  session whose last heartbeat has already fallen outside `GRACE_PERIOD` reads as `endedAt: null`
  until *another* heartbeat happens to arrive and close it — which, for a session nobody is
  extending, never happens. Left as `endedAt: null` forever, the admin timeline showed these as
  perpetually "in progress" no matter how old they were. `status` is computed at read time from
  `lastSeenAt` vs. now, not stored, so it needed `lastSeenAt` added to `SessionProjection` /
  `findSessionsByUserId`. Note this only fixes the *stale-forever* display bug — a user genuinely
  reopening the same POC after a >90s gap correctly gets a second, separate session row; that's
  the grace-period design working as intended, not something this change touches (see "Concurrent
  sessions" above and the frontend mockup work tracked separately for grouping same-POC sessions
  in the UI).
- 2026-08-26 — Initial implementation. Backend: `activity` package (entity/repository/service/
  mapper/controller) mirroring `poc`'s layout, migration `V9__create_activity_sessions_table.sql`,
  `openapi/components/schemas/activity.yaml` + four `Activity`-tagged paths, and a new
  `CurrentUser.id()` helper. Frontend: `core/activity/` (API wrapper, models, `formatDuration`),
  heartbeat wiring in `PocWorkspace`, and the `ActivityPlaceholder` stub renamed to `Activity` and
  built out, plus a new `ActivityUserDetail` page at `/activity/users/:id` (admin-gated, reusing
  the existing `adminGuard`). Verified end to end against a live Postgres: heartbeats accumulated
  21s across repeated pings, both leaderboards sorted correctly with names resolved via the entity
  joins, the drill-down returned the per-POC breakdown and session timeline, and a non-admin token
  was correctly allowed to heartbeat but refused all three reads.

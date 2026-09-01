# POC Catalog

## Status

Implemented

## Overview / Purpose

The portal dashboard shows a grid of "POC" (proof-of-concept AI demo) cards — originally a hardcoded array in `self-service-portal/src/app/data/pocs.ts` (`id`, `title`, `description`, `icon`). This feature moves that catalog into `self-service-api`'s database, with a full CRUD API, so POCs can be added/edited/removed without a frontend deploy, and wires the Angular dashboard/details/workspace pages to it — `data/pocs.ts` no longer exists.

## Requirements

- Store POC entries: name, description, an icon image URL, an app URL, a GitHub URL, version, owner, category, technologies used, a container image reference, demo type, and a lifecycle status.
- A way to list all POCs, and to create/read/update/delete individual POCs.
- The list endpoint must be callable without authentication (it feeds the dashboard, which renders the POC grid regardless of login state), but must not leak the app/GitHub launch links or the container image reference to anonymous callers — only the display fields (name, description, icon, version, owner, category, technologies, demo type, status).
- Every other operation (get one by id, create, update, delete) requires a bearer token, consistent with the rest of the API.

## Architecture Decisions

**Identifier: an incrementing `BIGINT` primary key, not a ULID or a slug.**
`users` uses a ULID string PK, and the frontend's current hardcoded POCs use human-readable slugs (`"contract-agent"`) as their `id`. Neither was used here — POCs are a small, admin-managed catalog, and a simple identity column is the most direct fit. The frontend's eventual `/poc/:id` routing scheme is a UI-phase decision, not constrained by this choice.

**Two response shapes: `PocSummaryResponse` (public) vs `PocResponse` (full, authenticated).**
The public list only returns `id`, `name`, `description`, `iconUrl` — enough to render a dashboard card. `appUrl`/`githubUrl` (the actual launch/repo links) are only returned from the authenticated single-resource endpoint (`GET /pocs/{id}`) and the write endpoints. `iconUrl` is on the public shape, not just the full one, because the icon is a display element for the same public card as name/description — unlike the launch links, showing it to an anonymous visitor reveals nothing sensitive. `PocResponse` is `allOf: [PocSummaryResponse, {appUrl, githubUrl}]`, the same composition style already used for `LoginResponse` (`allOf: [TokenResponse, {user}]`) in the JWT authentication feature.

**`icon_url` stores a URL, not an uploaded file.**
The API assumes the icon image already lives somewhere with a public URL (e.g. an S3/GCS/Azure Blob bucket) and just stores/returns that URL. Upload/asset-hosting is out of scope here.

**`containerImage` is gated the same as `appUrl`/`githubUrl`, the rest of the metadata is public.**
`version`, `owner`, `category`, `technologies`, `demoType`, and `status` were added to match an existing POC contract used elsewhere (fields like `name`, `version`, `owner`, `category`, `technologies`, `containerImage`, `demoType`, `status`). All of them are descriptive/display metadata for the same public dashboard card as name/description, so they're on `PocSummaryResponse` too. `containerImage` is the one exception — like `appUrl`/`githubUrl`, it's an internal deployment reference (an image registry path), not display content, so it only appears on the authenticated `PocResponse`.
*(Superseded 2026-09-01 — see `docs/specs/poc-deployment.md`: both `version` and `containerImage` were removed from `pocs` entirely, replaced by real per-version tracking. This paragraph is kept for history, not current behavior.)*

**`status` is now a closed `ACTIVE`/`HIDDEN` enum, doubling as the admin hide/unhide mechanism.**
Originally free text with only one known value (`"ACTIVE"`). Once admin hide/unhide (see Requirements/Data Model below) needed a visibility flag, `status` was the natural fit rather than adding a new boolean column — it had no other reader or writer anywhere in the codebase, and "hidden" is genuinely a lifecycle status, not a distinct concept. Tightened to an enum at the same time (`ACTIVE`, `HIDDEN`) since this round is the only writer of new values — free 400-on-typo validation instead of accepting arbitrary strings. This also resolves the "tighten `status` into an enum" item from Open Questions below (the `demoType` half of that item is still open).

**Soft-delete is a separate `deletedAt` column, independent of `status`.**
Deliberately not folded into `status` as a third value — hide/unhide (a routine, frequent toggle) and delete (a rarer, more consequential action with its own restore/undo UX) are different concerns, and a POC should be able to be both `HIDDEN` and deleted without those two facts colliding into one field. `deleted_at` is nullable; `NULL` means not deleted.

**Hide/unhide/delete/restore are dedicated single-purpose endpoints, not routed through `PUT`.**
`PUT /pocs/{id}` is a full replace (`PocService.applyFields` sets every field unconditionally — see API Surface below), so routing a visibility or delete toggle through it would require the caller to resend the entire object or risk wiping unrelated fields. `POST /pocs/{id}/hide`, `/unhide`, `/restore`, and the repurposed `DELETE /pocs/{id}` (now a soft delete) each do exactly one thing.

**Runtime auth for the public list needed an explicit `SecurityConfig` matcher, not just OpenAPI `security: []`.**
OpenAPI's per-operation `security: []` only affects generated documentation — Spring Security's actual authorization comes from `SecurityConfig.securityFilterChain()`'s own `requestMatchers(...)`. Everything not explicitly `permitAll()`-listed falls through to `.anyRequest().access(AuthorizationManagers.allOf(AuthenticatedAuthorizationManager.authenticated(), trialAuthorizationManager))`. Added `.requestMatchers(HttpMethod.GET, "/pocs").permitAll()`, scoped to `GET` only so `POST /pocs` and all of `/pocs/{id}` stay authenticated. (This exact class of mismatch — OpenAPI claiming public, Spring Security actually rejecting — is what broke CORS earlier in this repo's history; see the CORS changelog entry in `docs/specs/jwt-authentication.md`.)

**`category` options come from a `poc_categories` lookup table, not a closed enum.**
The admin add/edit form originally had `category` as free text. Once it needed to become a dropdown, the choice was a lookup table (like `pocs` itself) vs. a hardcoded enum on `CreatePocRequest`/`UpdatePocRequest`. A table was picked because `pocs.category` stays plain `VARCHAR` (unvalidated, exactly as before — this only changes the admin form's *input widget*, not the API contract), and because the value set is expected to grow without a deploy: adding a category is an `INSERT`, not a migration. `poc_categories` has no relationship to `pocs.category` (no FK) — it's purely a source list for the dropdown, matching the "for now, static" scope the seeded rows started from.

## Data Model

**`pocs`** (migration `V4__create_pocs_table.sql`, entity `poc/entity/Poc.java`)
| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| name | VARCHAR(200) NOT NULL | |
| description | VARCHAR(2000) NOT NULL | |
| icon_url | VARCHAR(500) | nullable, public URL to an icon image |
| app_url | VARCHAR(500) | nullable, deployed POC app URL |
| github_url | VARCHAR(500) | nullable, source repo URL |
| ~~version~~ | ~~VARCHAR(50)~~ | removed 2026-09-01 — see `docs/specs/poc-deployment.md` (`active_version_id` below) |
| owner | VARCHAR(200) | nullable, owning team/person |
| category | VARCHAR(100) | nullable |
| technologies | TEXT\[\] NOT NULL DEFAULT '{}' | |
| ~~container_image~~ | ~~VARCHAR(500)~~ | removed 2026-09-01 — moved to `poc_versions.container_image`, see `docs/specs/poc-deployment.md` |
| demo_type | VARCHAR(50) | nullable |
| status | VARCHAR(50) NOT NULL DEFAULT 'ACTIVE' | `ACTIVE` \| `HIDDEN` (enum-validated at the API layer; column itself is still plain text) |
| details | TEXT | nullable, longer-form copy for the public details page |
| guide_steps | TEXT\[\] NOT NULL DEFAULT '{}' | ordered "how to use this POC" steps, public details page |
| created_at / updated_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |
| deleted_at | TIMESTAMPTZ | nullable; `NULL` = not deleted (migration `V8__add_deleted_at_to_pocs_table.sql`) |
| active_version_id | BIGINT REFERENCES poc_versions(id) | nullable; added 2026-09-01, see `docs/specs/poc-deployment.md` |

**`poc_categories`** (migration `V14__create_poc_categories_table.sql`, entity `poc/entity/PocCategory.java`)
| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| name | VARCHAR(100) NOT NULL UNIQUE | |

Seeded with 4 rows: `Healthcare`, `RAG`, `Process Assistant`, `Accelerators`.

## API Surface

All new endpoints live under `/pocs`, tagged `Poc` (generates a single `PocApi` interface, same pattern as `Auth`/`User`/`Support`).

- **`GET /pocs`** — public, but response contents now depend on the caller. Anonymous and non-admin callers always get `status=ACTIVE AND deletedAt IS NULL` rows only. Authenticated admins additionally see `HIDDEN` rows, and — only when the new `includeDeleted=true` query param is passed — soft-deleted rows too (`includeDeleted` is ignored for non-admins). Returns `PocSummaryResponse[]` (`id`, `name`, `description`, `iconUrl`, `version`, `owner`, `category`, `technologies`, `demoType`, `status`, `details`, `guideSteps`, `deletedAt`) — `deletedAt` is always `null` in a non-admin response, since those rows are pre-filtered.
- **`GET /pocs/{id}`** — authenticated. Returns `PocResponse` (adds `appUrl`, `githubUrl`, `containerImage`). `404` if not found, or if soft-deleted and the caller isn't an admin.
- **`POST /pocs`** — **admin only**. `CreatePocRequest {name, description, iconUrl?, appUrl?, githubUrl?, version?, owner?, category?, technologies?, containerImage?, demoType?, status?}` → `201` `PocResponse`. `status` defaults to `"ACTIVE"` if omitted.
- **`PUT /pocs/{id}`** — **admin only**. `UpdatePocRequest` (same shape as create — full replace of the mutable fields, not a partial patch) → `200` `PocResponse`. `404` if not found.
- **`DELETE /pocs/{id}`** — **admin only**. Soft delete: sets `deletedAt`, does not remove the row. `204`. `404` if not found (idempotent-on-404 is *not* assumed here, unlike `/auth/logout`).
- **`POST /pocs/{id}/hide`** — **admin only** (new). Sets `status=HIDDEN`. `200` `PocResponse`. `404` if not found.
- **`POST /pocs/{id}/unhide`** — **admin only** (new). Sets `status=ACTIVE`. `200` `PocResponse`. `404` if not found.
- **`POST /pocs/{id}/restore`** — **admin only** (new). Clears `deletedAt`. `200` `PocResponse`. `404` if not found.
- **`GET /pocs/categories`** — public (new). Returns `PocCategoryResponse[]` (`id`, `name`), alphabetical, for the admin add/edit form's category dropdown. No auth required, same reasoning as `GET /pocs`: nothing sensitive in a list of category names.

## Security Considerations

- `GET /pocs` is intentionally public — it only ever exposes name/description/icon (never the launch or source links) to non-admins, so there's nothing sensitive in the anonymous/regular-user response. Admin-only fields stay gated exactly as before; the only new role-dependent behavior is which *rows* are included, not which *fields*.
- Write operations (`POST`/`PUT`/`DELETE`/`hide`/`unhide`/`restore`) now require the `ADMIN` role (`@PreAuthorize("hasRole('ADMIN')")`, see `docs/specs/admin-users.md` for the full role-gating design) — resolves the "Admin-only writes" open question below.

## Open Questions / Future Work

- **Icon upload**: no asset-hosting/upload endpoint exists; `iconUrl` must be populated with an already-hosted URL. The portal's `PocCard` renders it as an `<img>` when present, falling back to a generic SVG glyph when absent (no POC has a real `iconUrl` set yet).
- **`demoType` as a closed enum**: still free-text `VARCHAR`. `status` was tightened this round (see Architecture Decisions); `demoType` wasn't, since its valid value set still isn't known — same reasoning that originally applied to both.
- **Hard-delete / purge**: soft-deleted POCs are retained indefinitely once restorable via the admin "Show deleted" view — there's no purge job or hard-delete path. Not needed yet; revisit if the deleted set grows large.
- **Versioning and deployment tracking**: now covered by `docs/specs/poc-deployment.md` (2026-09-01) — see that spec for `version`/`containerImage`'s removal from this table and the new `poc_versions`/`poc_deployments` model.

## Angular UI Wiring

Three portal pages consume this API, split along the same public/authenticated line as the backend:

- **`Dashboard`** (`/dashboard`, public) and **`PocDetails`** (`/poc/:id`, public) both call `PocApi.getPocs()` — the public summary list — and `PocDetails` finds its POC by id client-side rather than a dedicated single-POC public endpoint (none exists; `GET /pocs/{id}` is deliberately authenticated, since it carries `appUrl`/`githubUrl`/`containerImage`). Refetching the whole list to show one POC is a deliberate small tradeoff given the catalog's size, not worth a new endpoint for.
- **`PocWorkspace`** (`/poc/:id/workspace`, behind `authGuard`) calls `PocApi.getPocById(id)` — the authenticated full response — since it needs `appUrl` to embed the POC's app in an iframe. This is exactly why `appUrl` was gated to authenticated callers in the first place: the one place that needs it is already behind a login wall.
- The frontend's `Poc` type (previously its own vocabulary: `title`, `url`, a closed `PocIcon` enum) was retired in favor of `PocSummary`/`PocDetail` (`core/poc/poc.models.ts`), which mirror `PocSummaryResponse`/`PocResponse` directly — `id` is now numeric, matching the backend's identity column, and POC routes (`/poc/:id`, `/poc/:id/workspace`) now resolve against that numeric id instead of the old hardcoded string slugs.
- `details`/`guideSteps` (shown on the public `PocDetails` page) didn't exist in the original backend contract — added here (public, alongside the other display fields) once discovered mid-wiring, following the same reactive-extension pattern as `version`/`owner`/`category`/etc.
- Seeded the 5 previously-hardcoded POCs into the database (`V7__seed_initial_pocs.sql`) so the dashboard doesn't regress to empty — only fields with real values were populated (mostly just name/description; `sails-process-assistant` also got its real `appUrl`/`details`/`guideSteps`). Everything else (`version`, `owner`, `category`, `technologies`, `iconUrl`, `containerImage`, `demoType`) is left null rather than filled with fabricated placeholder data.
- `PocFormModal`'s category field is a native `<select>`, populated from `PocApi.getCategories()` (fetched once per modal open, in both create and edit mode). It's a plain HTML `<select>` bound via `formControlName="category"`, not a custom combobox, since the option count is small and no search/filter is needed. An existing POC whose stored `category` doesn't match any current row (e.g. legacy seed data) shows no option selected but keeps its original value in the form until the admin explicitly changes it — the dropdown doesn't clear or coerce the underlying field.

## Changelog

- 2026-09-01 — Added `poc_categories` (migration `V14`) and public `GET /pocs/categories`, and changed the admin add/edit form's category field from free text to a dropdown sourced from it. `pocs.category` itself is unchanged (still plain `VARCHAR`, no FK) — this only replaces the form's input widget.
- 2026-09-01 — `version`/`container_image` removed from `pocs`, replaced by `active_version_id` (FK
  to a new `poc_versions` table) and real deployment tracking. See `docs/specs/poc-deployment.md`.
- 2026-08-26 — Admin-only writes: `POST/PUT/DELETE /pocs` now require the `ADMIN` role (see `docs/specs/admin-users.md`). `DELETE` is now a soft delete (`deleted_at`, migration `V8`) with a new `POST /pocs/{id}/restore`. `status` tightened to an `ACTIVE`/`HIDDEN` enum and repurposed as the hide/unhide mechanism, via new `POST /pocs/{id}/hide` and `/unhide`. `GET /pocs` gained an admin-only `includeDeleted` query param and now filters rows by caller role instead of returning everything unconditionally.
- 2026-08-25 — Wired the Angular dashboard/details/workspace pages to this API (see "Angular UI Wiring" above) and removed `data/pocs.ts`. Added `details` (`TEXT`) and `guideSteps` (`TEXT[]`) — public fields, migration `V6__add_details_and_guide_steps_to_pocs_table.sql` (additive, not folded into `V4`, since that migration is now committed/shared) — plus a seed migration (`V7__seed_initial_pocs.sql`) for the 5 pre-existing hardcoded POCs. Refactored `PocService.create`/`update` from 12 (now would've been 14) positional same-type parameters into a `PocFields` record, to remove a real transposition-bug risk that had grown past the point the `UserService.registerUser`-style positional-parameter precedent was comfortable.
- 2026-08-25 — Added `version`, `owner`, `category`, `technologies`, `containerImage`, `demoType`, `status` to match an existing POC contract (fields observed: `name`, `version`, `owner`, `category`, `technologies`, `containerImage`, `demoType`, `status`). Revised `V4__create_pocs_table.sql` in place rather than adding a new migration, since the table hadn't been committed or applied anywhere yet. `containerImage` joined `appUrl`/`githubUrl` as authenticated-only; the rest joined the public `PocSummaryResponse`.
- 2026-08-24 — Initial draft and implementation: `pocs` table, `PocApi` (list/get/create/update/delete), public `GET /pocs` summary endpoint.

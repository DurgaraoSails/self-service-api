# POC Catalog

## Status

In Progress

## Overview / Purpose

The portal dashboard shows a grid of "POC" (proof-of-concept AI demo) cards — currently a hardcoded array in `self-service-portal/src/app/data/pocs.ts` (`id`, `title`, `description`, `icon`). This feature moves that catalog into `self-service-api`'s database, with a full CRUD API, so POCs can be added/edited/removed without a frontend deploy. This spec covers the backend only; wiring the Angular dashboard to the new API is deferred to a later pass.

## Requirements

- Store POC entries: name, description, an icon image URL, an app URL, and a GitHub URL.
- A way to list all POCs, and to create/read/update/delete individual POCs.
- The list endpoint must be callable without authentication (it feeds the dashboard, which renders the POC grid regardless of login state), but must not leak the app/GitHub launch links to anonymous callers — only name, description, and icon.
- Every other operation (get one by id, create, update, delete) requires a bearer token, consistent with the rest of the API.

## Architecture Decisions

**Identifier: an incrementing `BIGINT` primary key, not a ULID or a slug.**
`users` uses a ULID string PK, and the frontend's current hardcoded POCs use human-readable slugs (`"contract-agent"`) as their `id`. Neither was used here — POCs are a small, admin-managed catalog, and a simple identity column is the most direct fit. The frontend's eventual `/poc/:id` routing scheme is a UI-phase decision, not constrained by this choice.

**Two response shapes: `PocSummaryResponse` (public) vs `PocResponse` (full, authenticated).**
The public list only returns `id`, `name`, `description`, `iconUrl` — enough to render a dashboard card. `appUrl`/`githubUrl` (the actual launch/repo links) are only returned from the authenticated single-resource endpoint (`GET /pocs/{id}`) and the write endpoints. `iconUrl` is on the public shape, not just the full one, because the icon is a display element for the same public card as name/description — unlike the launch links, showing it to an anonymous visitor reveals nothing sensitive. `PocResponse` is `allOf: [PocSummaryResponse, {appUrl, githubUrl}]`, the same composition style already used for `LoginResponse` (`allOf: [TokenResponse, {user}]`) in the JWT authentication feature.

**`icon_url` stores a URL, not an uploaded file.**
The API assumes the icon image already lives somewhere with a public URL (e.g. an S3/GCS/Azure Blob bucket) and just stores/returns that URL. Upload/asset-hosting is out of scope here.

**Runtime auth for the public list needed an explicit `SecurityConfig` matcher, not just OpenAPI `security: []`.**
OpenAPI's per-operation `security: []` only affects generated documentation — Spring Security's actual authorization comes from `SecurityConfig.securityFilterChain()`'s own `requestMatchers(...)`. Everything not explicitly `permitAll()`-listed falls through to `.anyRequest().access(AuthorizationManagers.allOf(AuthenticatedAuthorizationManager.authenticated(), trialAuthorizationManager))`. Added `.requestMatchers(HttpMethod.GET, "/pocs").permitAll()`, scoped to `GET` only so `POST /pocs` and all of `/pocs/{id}` stay authenticated. (This exact class of mismatch — OpenAPI claiming public, Spring Security actually rejecting — is what broke CORS earlier in this repo's history; see the CORS changelog entry in `docs/specs/jwt-authentication.md`.)

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
| created_at / updated_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |

## API Surface

All new endpoints live under `/pocs`, tagged `Poc` (generates a single `PocApi` interface, same pattern as `Auth`/`User`/`Support`).

- **`GET /pocs`** — public (`security: []` + `SecurityConfig` matcher). Returns `PocSummaryResponse[]` (`id`, `name`, `description`, `iconUrl`).
- **`GET /pocs/{id}`** — authenticated. Returns `PocResponse` (adds `appUrl`, `githubUrl`). `404` if not found.
- **`POST /pocs`** — authenticated. `CreatePocRequest {name, description, iconUrl?, appUrl?, githubUrl?}` → `201` `PocResponse`.
- **`PUT /pocs/{id}`** — authenticated. `UpdatePocRequest` (same shape as create — full replace of the mutable fields, not a partial patch) → `200` `PocResponse`. `404` if not found.
- **`DELETE /pocs/{id}`** — authenticated. `204`, idempotent-on-404 is *not* assumed here (unlike `/auth/logout`) — deleting a nonexistent id returns `404`.

## Security Considerations

- `GET /pocs` is intentionally public — it only ever exposes name/description/icon, never the launch or source links, so there's nothing sensitive in the anonymous response.
- Write operations (`POST`/`PUT`/`DELETE`) have no additional role check beyond "authenticated" — any logged-in user can currently manage the POC catalog, same blanket authorization as the rest of `/pocs/**`'s non-public routes (via `SecurityConfig`'s `anyRequest()` branch, which also applies `trialAuthorizationManager`). If POC management should be admin-only, that's a follow-up (see Open Questions).

## Open Questions / Future Work

- **Admin-only writes**: right now any authenticated (non-expired-trial) user can create/update/delete POCs. Worth revisiting with a role check (`roles` already exists on `User`) once there's an actual admin surface.
- **Angular UI wiring**: the dashboard, `PocCard`, and `PocWorkspace` components still consume the hardcoded `data/pocs.ts` array. Wiring them to this API (including deciding how `/poc/:id` routing should work against a numeric id) is deferred to a later pass.
- **Icon upload**: no asset-hosting/upload endpoint exists; `iconUrl` must be populated with an already-hosted URL.

## Changelog

- 2026-08-24 — Initial draft and implementation: `pocs` table, `PocApi` (list/get/create/update/delete), public `GET /pocs` summary endpoint.

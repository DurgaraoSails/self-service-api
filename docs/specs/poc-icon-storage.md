# POC Icon Upload & Storage

## Status

Draft — for team review. Not implemented.

## Overview / Purpose

An admin sets a POC's icon today by picking an image that `self-service-portal` converts to a base64
`data:` URI and sends inline as `iconUrl` in the POC create/update JSON. **This has never worked.**
`pocs.icon_url` is `VARCHAR(500)` (`V4__create_pocs_table.sql:5`, never altered by any later
migration), and 500 characters of base64 is roughly 370 bytes — smaller than any real image. Every
upload fails with `iconUrl: size must be between 0 and 500`. Widening the OpenAPI `maxLength` alone
would only move the failure from a clean 400 to a Postgres *"value too long for type character
varying(500)"* 500.

Inlining is also the wrong shape independently of the column width. `GET /pocs` is unpaginated and
returns every POC in a single response, and the dashboard re-fetches that list **every 10 seconds**
while any deployment is in progress (`DASHBOARD_DEPLOYMENT_POLL_INTERVAL_MS` in `dashboard.ts`).
Every icon's bytes would ride along on every poll, inflated ~33% by base64, and could never be
cached by the browser because they are part of a JSON body rather than a cacheable image response.

This feature stores an uploaded icon as a real object in a **public GCS bucket** under a
content-hashed key, returns an absolute URL in `iconUrl`, and lets the browser load it with a plain
`<img>`. After the POC list response — which the portal already fetches — icons cost
`self-service-api` nothing, and a repeat visit serves them from the browser's disk cache with **zero
network requests**.

## Requirements

- An admin uploads an image file; the platform stores the object and keeps only a reference in the
  database.
- The dashboard renders every POC's icon on initial load without a per-icon call to
  `self-service-api`.
- A repeat visit must not re-download icons that have not changed.
- Replacing a POC's icon must take effect immediately for every user, with no stale-cache window.
- Supported types: PNG, JPEG, WebP and SVG, capped at 150KB.
- No behaviour is lost for any POC whose `icon_url` currently holds a genuine externally-hosted URL.
- Icons must not be written into the existing private user-files bucket.

## Architecture Decisions

**Icons are served from a public bucket, fetched directly by the browser.** The DB stores an object
key; `PocResponseMapper` composes an absolute URL into the existing `iconUrl` response field; the
portal renders it with a plain `<img [src]>`. Content-hashed object names allow
`Cache-Control: public, max-age=31536000, immutable`, so an icon is fetched at most once per browser
ever.

*Rejected: serving icons through `self-service-api`* (e.g. `GET /pocs/{id}/icon` with cache headers).
It works and needs no new bucket, but every cold dashboard load becomes N requests to the API for
data that is neither private nor dynamic, and the API pays the bandwidth for every new visitor.

*Rejected: one bundled endpoint returning all icons as base64 with an ETag.* This is the closest fit
to "no per-file request at all" and needs no infra change, but it re-downloads every icon whenever
any single one changes, keeps the 33% base64 overhead, defeats the browser's own image cache, and
grows linearly with POC count — reintroducing the problem this feature exists to remove.

*Rejected: signed URLs.* `FileStorage`'s own javadoc records that signed URLs need a project-level
IAM grant this project is currently blocked on. They would also expire, which is fundamentally
incompatible with immutable caching.

Public read is consistent with this platform's established posture: `GET /pocs` is already
`permitAll` (`SecurityConfig`), and POCs themselves deploy `--allow-unauthenticated` by default
(`poc-manifest-deployment.md`). A POC icon is branding shown to every visitor of a public catalogue
endpoint; it is not sensitive.

**A separate `PocIconStorage`, not a reuse of `FileStorage`.** `file/storage/FileStorage.java` cannot
serve this. Its GCS implementation writes to `files.bucket` — the *private* user-files bucket — and
sets `Content-Disposition: attachment`, which is deliberately hostile to inline `<img>` rendering.
Icons need the opposite on every axis:

| | user files (`FileStorage`) | icons (`PocIconStorage`) |
|---|---|---|
| visibility | private, JWT + trial gated | public |
| disposition | `attachment` | inline |
| caching | none | immutable, one year |
| quota | per-user bytes + per-POC count | none |
| scope | `(userId, pocId)` | `pocId` |

Two implementations mirroring the existing pattern (`@ConditionalOnProperty(prefix="files",
name="storage", …)`, `matchIfMissing=true` for local), reusing the `Storage` bean already built by
`file/config/GcsStorageConfig.java`. The duplication is ~60 lines and is the point: overloading
`FileStorage` with disposition/cache/bucket parameters would make every user-file call site carry
options that only exist for icons.

**Object keys are content-addressed and POC-scoped:** `poc-icons/<pocId>/<sha256-of-bytes>.<ext>`.

Content-hashing is what makes `immutable` *safe* rather than reckless — replacing an icon writes a
new key, so the URL changes and no browser can serve a stale image. There is no cache-invalidation
problem to solve because there is never anything to invalidate.

Scoping by `pocId` deliberately forgoes cross-POC deduplication. If two POCs uploaded the same image
they would share one object, and deleting one POC's icon would silently break the other's — a
reference-counting problem not worth having for a few kilobytes.

**A `poc-icons.*` config namespace, separate from `files.*`.** Same separation-of-concerns precedent
as splitting `poc-runtime.*` out of `pipeline.*`: `files.*` describes private user storage, and
overloading it would imply icons share its bucket and quotas.

```yaml
poc-icons:
  bucket: ${POC_ICONS_BUCKET:}
  # Absolute base prepended to an object key to build iconUrl.
  #   gcs:   https://storage.googleapis.com/sails-agenthub-poc-icons
  #   local: http://localhost:8080/public/poc-icons
  public-base-url: ${POC_ICONS_PUBLIC_BASE_URL:http://localhost:8080/public/poc-icons}
  local-dir: ${POC_ICONS_LOCAL_DIR:}
```

The local-vs-gcs switch reuses the existing `files.storage` value rather than adding a second mode
that could disagree with it. A blank `bucket` under `files.storage=gcs` must fail at startup, exactly
as `files.bucket` already does.

**Local development serves icons from the already-permitted `/public/**` prefix.** `SecurityConfig`
lists `/public/**` as `permitAll` with nothing currently mapped under it. A
`GET /public/poc-icons/{pocId}/{filename}` controller streams bytes with the same immutable
`Cache-Control` — following `security/JwksController.java:42`, the only existing `CacheControl` usage
in the codebase.

In `gcs` mode `public-base-url` points at the bucket, so this endpoint is never exercised. One code
path; configuration decides where the bytes come from, and local development works without a bucket.

**SVG is supported, sanitised on upload.** `ContentTypeValidator` today sniffs magic bytes and
supports PNG and JPEG only; it fails at startup if `allowed-content-types` names a type it has no
signature for, so both additions require code:

- **WebP** — `RIFF` at offset 0 and `WEBP` at offset 8.
- **SVG** — has no magic number; it is XML text. Validated by parsing it and requiring a root `<svg>`
  element, with the parser hardened against XXE (`disallow-doctype-decl`, external general and
  parameter entities disabled, `FEATURE_SECURE_PROCESSING`).

Sanitisation walks the parsed DOM and removes `<script>`, `<foreignObject>`, every `on*` attribute,
and any `href`/`xlink:href` that is not a fragment or a `data:image/*` URI, then re-serialises. The
**sanitised** bytes are stored; the original is discarded. A document that fails to parse is
rejected rather than repaired.

*Alternative considered: rejecting SVG entirely.* It is the safer default and was the initial
recommendation, but the portal's upload UI already offers SVG and admins reasonably want a crisp
vector logo at any display size. Given the exposure analysis below, sanitisation plus cross-origin
serving is a proportionate answer rather than a compromise.

**The data model change is purely additive; `icon_url` is kept.** Nothing has ever successfully
written a data URI to `icon_url`, but it may hold genuine externally-hosted URLs — the OpenAPI
description documents it as *"Public URL … hosted in a storage bucket"* with a `cdn.example.com`
example. `PocResponseMapper` resolves `iconUrl` as: `icon_object_key` present → compose
`{public-base-url}/{key}`; otherwise fall back to the stored `icon_url`. No data migration, no
behaviour lost, and the legacy path can be dropped later once no rows use it.

**`iconUrl` is removed from `CreatePocRequest`/`UpdatePocRequest`.** Leaving it would provide two
ways to set one thing with different semantics — an inline value that bypasses validation, storage
and sanitisation entirely. Icons become managed solely through the dedicated endpoints. This is a
breaking contract change, but `self-service-portal` is the only client.

**The portal must never fetch an icon through `HttpClient`.**
`core/http/http-interceptor.ts:24-30` attaches `Authorization: Bearer <token>` to *every* outgoing
request except those under the `/auth` prefix — with no host check. An `HttpClient` GET to
`storage.googleapis.com` would therefore leak the portal's JWT to Google, trigger a CORS preflight
that fails, and — on a 401 — kick off a spurious single-flight token refresh that can log the user
out. A plain `<img [src]>` bypasses `HttpClient` entirely, which is precisely the mechanism this
design depends on and should be stated in a comment at the render site.

## Data Model

**`pocs`** (migration `V21__add_poc_icon_object_key.sql` — V20 is the current highest) gains:

| column | type | notes |
|---|---|---|
| icon_object_key | VARCHAR(255) NULL | Storage key, e.g. `poc-icons/7/a3f9…c1.png`. NULL means no uploaded icon. |

`icon_url VARCHAR(500)` is retained unchanged as the legacy external-URL fallback. `Poc.java` gains
a matching `iconObjectKey` field beside the existing `iconUrl`.

No new table: an icon is a single-valued attribute of a POC, not a collection, and unlike
`user_files` it carries no per-user ownership, quota or soft-delete semantics that would justify one.

## API Surface

Both new endpoints are admin-only via method-level `@PreAuthorize("hasRole('ADMIN')")`, matching the
existing POC mutations in `PocController`. Multipart bodies follow the shape the user-file upload
endpoints already use in `openapi/`.

**`POST /pocs/{id}/icon`** — multipart `file`. Validates type and size, sanitises SVG, stores the
object, updates `icon_object_key`, deletes the previously referenced object, and returns the updated
`PocResponse`.

**`DELETE /pocs/{id}/icon`** — clears `icon_object_key` and deletes the object.

**`GET /public/poc-icons/{pocId}/{filename}`** — `permitAll`, streams bytes with
`Cache-Control: public, max-age=31536000, immutable`. Exercised in local mode only.

**Changed schemas** (`openapi/components/schemas/poc.yaml`):

- `PocSummaryResponse.iconUrl` — `maxLength` raised from 500 to 1024. It is a real URL again, and a
  composed bucket URL comfortably exceeds 500.
- `CreatePocRequest.iconUrl`, `UpdatePocRequest.iconUrl` — **removed**.

`GET /pocs` is unchanged in shape and remains `permitAll` and unpaginated.

## Security Considerations

- **The icons bucket must be new and separate.** It cannot be `sails-agenthub-poc-files`, which holds
  private per-user uploads; making that bucket public would expose every user file in the platform.
  A new bucket (e.g. `sails-agenthub-poc-icons`) with uniform bucket-level access and
  `allUsers → roles/storage.objectViewer`. No CORS configuration is required — `<img>` loads are not
  CORS-restricted.
- **SVG is the highest-risk element of this design.** An SVG loaded through `<img>` cannot execute
  script — browsers neuter it — so the dashboard itself is not exposed. The residual risk is someone
  opening an object URL directly, where script would run in the `storage.googleapis.com` origin
  rather than the portal's, and therefore cannot reach portal tokens or `localStorage`. That is an
  abuse/phishing vector on a Google-owned domain, not a portal XSS. Upload-time sanitisation is what
  reduces it further, and is the part of this feature most deserving of thorough tests.
- **Upload is admin-only.** Only an admin can put bytes into a public bucket, which bounds the abuse
  surface to accounts that can already create and deploy POCs.
- **The XML parser must be XXE-hardened** before it sees an uploaded SVG. A naive
  `DocumentBuilderFactory` would allow external entity references that could read files from the API
  container or make outbound requests from it.
- **The portal's blanket `Authorization` header** (see Architecture Decisions) means any future
  attempt to load icons via `HttpClient`, a `fetch` wrapper, or a service worker would leak the
  portal JWT cross-origin. Loading via `<img>` is a correctness requirement, not a stylistic
  preference.
- **Content-hashed keys are not a security boundary.** They make a URL hard to guess, but the bucket
  is public by design; nothing confidential may ever be stored there.
- **Orphaned objects.** Deleting a POC should delete its icon object. Until that exists, deleted
  POCs leave paid objects behind — see Future Work.

## Implementation Phases

Each phase is independently reviewable; the feature is only user-visible after Phase 3.

**Phase 1 — storage and validation.** `PocIconProperties`, `PocIconStorage` + both implementations,
WebP and SVG signatures in `ContentTypeValidator`, the SVG sanitiser, and the
`V21__add_poc_icon_object_key.sql` migration with the `Poc` entity field. Fully unit-testable with no
API surface.

**Phase 2 — API.** The two admin endpoints, `PublicPocIconController`, `PocResponseMapper` URL
composition, and the OpenAPI changes.

**Phase 3 — portal.** `PocApi.uploadIcon`/`deleteIcon`; extract the duplicated icon-picker logic
(`poc-form-modal.ts:140-165` and `poc-settings-page.ts:229-254` are byte-for-byte identical today,
including their three error strings) into one shared helper; sequence create-then-upload in the
create modal; harden `poc-card.html:99-100` with explicit `width`/`height` (the wrapper is 44px),
`loading="lazy"`, and an `(error)` fallback to the existing inline SVG placeholder — which today
only covers a *falsy* URL, an impossibility with data URIs but entirely possible with a remote one.

**Sequencing note for create.** A POC has no id until it exists, so the create modal must create the
POC first and then upload the icon. A failed icon upload must not roll back the created POC; it
should surface an error and leave the admin to retry from the Settings page.

## Open Questions / Future Work

- **Icon deletion on POC deletion** is not covered by this spec. `pocs` rows are soft-deleted
  (`deleted_at`), so an icon arguably should survive a restore — which makes "delete the object"
  wrong until hard deletion exists. Needs a decision before Phase 2 ships.
- **No thumbnailing.** Icons render at 44px but a 150KB original is stored and served as-is
  (a deliberate decision to keep this change focused). The cost is paid once per icon per browser
  thanks to immutable caching. If first-load weight becomes a problem, JDK `ImageIO` can produce a
  ~5KB normalised image with no new dependency for PNG/JPEG — but would need care around
  decompression bombs, EXIF orientation and transparency, and cannot process SVG.
- **No CDN.** `public-base-url` is deliberately a plain configuration value, so putting Cloud CDN or
  another origin in front of the bucket later is a config change with no code impact.
- **`firebase.json` has no `headers` block**, so the portal's own static assets are served without
  `Cache-Control` tuning. Out of scope here, but the same class of problem and worth a follow-up.
- **Legacy `icon_url` rows.** Once no POC relies on an externally-hosted URL, the fallback branch in
  `PocResponseMapper` and the column itself can be dropped.
- **Bulk upload / defaults.** There is no way to set a default icon per category, nor to upload icons
  for many POCs at once. Not needed at current catalogue size.

## Changelog

- 2026-09-08 — Initial draft, written before implementation per `docs/specs/README.md`. Arose from a
  live bug (`iconUrl: size must be between 0 and 500`) that turned out to be unfixable by widening
  the column: the inline data-URI approach was also being re-transmitted on the dashboard's 10-second
  deployment poll and could never be cached. Three design decisions were taken with the team's
  reviewer before drafting: serve from a public bucket rather than proxying or bundling; support all
  four image types including SVG rather than dropping it; and store originals rather than generating
  thumbnails.

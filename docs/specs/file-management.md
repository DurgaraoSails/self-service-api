# File Management

## Status

In Progress — implementation started 2026-09-03 on branch `file-management`. See Implementation Plan
for the phase breakdown and what has landed.

## Overview / Purpose

Users upload documents; the POC they launch reads those documents and works on them. Nothing supporting this exists today — `self-service-api` has no multipart handling, no GCS integration, and no storage endpoints of any kind.

This is a prerequisite of `docs/specs/poc-hosting-architecture.md` rather than a part of it. That spec's Tier 0 storage model — POCs that persist nothing and rebuild their working state from platform-held files each session — depends on this feature completely, and the platform's trial-expiry purge obligation mostly lives here, since uploaded files are where the substantive volume of user data sits.

## Requirements

- A signed-in user can upload files, list them, download them, and delete them.
- A POC can read the files belonging to the user currently using it, as raw bytes.
- Files persist across POC launches — a user returning a week later finds their documents.
- Files are destroyed after the user's trial expires, subject to a grace period.
- A POC must not be able to reach another user's files, or the same user's files belonging to a different POC.
- The platform performs no parsing, extraction, or transformation. POCs receive the bytes as uploaded.
- Uploads must be bounded. This is a trial portal shown to prospects, where an unbounded upload path is a cost and abuse surface.

## Architecture Decisions

### Scope and ownership

**Files are scoped to a `(user, POC)` pair, not to a global per-user library.**
A document uploaded for the contract-review POC is not visible to any other POC. The alternative — one library per user, readable by everything they launch — is simpler and matches a literal reading of "load the files this user uploaded", but it means a user trialling five POCs exposes every document to all five, including POCs built by teams with no reason to see them. Scoping to the pair also makes the purge boundary, the authorization rule, and the storage layout the same shape, so there is one concept rather than three. The cost is that a user wanting the same document in two POCs uploads it twice, acceptable at demo scale. A third option — a shared library with explicit per-launch grants — has the best privacy and UX properties but requires a picker UI and a grants table, and is deferred rather than rejected.

**The platform owns storage and retrieval; the upload interface lives inside the POC, shipped as a component of the bridge library.**
Two things that sound contradictory are both true: upload belongs in the POC's own layout, where the team can place it in their flow rather than accepting portal chrome around the frame — and no POC team should be writing multipart handling or inventing a document model. Putting the uploader in the shared bridge library satisfies both. The component renders inside the POC, calls the platform API directly from the browser, and is the same component everywhere, so upload behaviour, progress, error handling, and quota messaging stay consistent without any POC implementing them.

What the platform retains is everything that matters for correctness: the bucket, the object layout, quota enforcement, the `(user, POC)` scoping rule, the metadata model, and purge. A POC never touches GCS and never decides what a document is.

**These endpoints are the first group of a POC-facing platform API, not a standalone file feature.**
Files are the first thing POCs consume from the platform, but not the last. Tier 1 per-user state (`poc-hosting-architecture.md`) is consumed by the same caller, with the same token, under the same claim-derived scoping, returning the same error shapes. Treating this as one versioned POC-facing surface means POC teams integrate with exactly two things — the bridge library in the browser and the platform API on the server — and learn nothing new when state endpoints arrive. The `/poc-files` prefix is chosen with that grouping in mind.

### Storage layout

**The object path is user-first: `users/<userId>/pocs/<pocId>/<fileId>`.**
Purge is the dominant bulk operation and it is scoped to a user, so putting the user first makes it a single prefix delete regardless of how many POCs they touched. Per-POC listing remains a clean prefix underneath, so nothing is lost. The reverse ordering would turn every purge into a scan across every POC prefix in the bucket — the same work, done worse.

**The path uses `poc_id`, not `slug`.**
`slug` is unique and is already the identifier in image paths (`poc-images/<slug>/app:<tag>`), but it is descriptive text that could be corrected or renamed. An identity column cannot. A renamed slug must not orphan a user's documents.

**Objects are named by generated id; the original filename is metadata only.**
The uploaded filename never appears in the object path. Three reasons, in increasing order of importance: it avoids path traversal and control-character handling entirely; it avoids collisions when a user uploads `report.pdf` twice; and the filename is frequently personal data in its own right — `Q3-layoffs-J-Smith.pdf` discloses its contents. A filename embedded in an object path is visible in bucket listings, access logs, and audit trails, which outlive the object itself, so it would survive a purge meant to remove it. `original_filename` is stored as a column and returned in API responses.

### Transport

**Bytes proxy through `self-service-api`; signed URLs are rejected.**
The conventional design — the API mints a V4 signed `PUT` URL and the browser uploads directly to GCS — is better on nearly every axis except the one that decides it here. Signing from a Cloud Run service account requires `roles/iam.serviceAccountTokenCreator`, a project-level IAM binding, and this project is already blocked on exactly that class of grant: `poc-deployment-pipeline.md` records five bindings commented out in `self-service-terraform` awaiting an IT-granted role the applying account does not hold.

What makes the proxy design buildable is a detail from that same investigation. The *bucket-level* binding is the one category that already succeeded, because Cloud Storage's legacy ACL compatibility layer is the single exception to `roles/editor`'s exclusion of `setIamPolicy`. The grant this feature needs is therefore the one grant already demonstrated to work in this project, while the signed-URL design would add a new dependency on the blocker.

The API contract below is deliberately shaped so the upload mechanism can be swapped for signed URLs later without changing how a client lists, reads, or deletes.

**Quotas are enforced rather than assumed, with a 10 MB per-file ceiling.**
Configurable defaults: 10 MB per file, 20 files per `(user, POC)`, and 250 MB total per user across all POCs. A trial portal open to prospects is precisely the context where an unbounded upload path becomes a cost problem, and a limit present from the first release avoids retrofitting one onto users who have already exceeded it.

10 MB still sits well below Cloud Run's 32 MiB request ceiling — under a third of it — which preserves what makes the proxy design simple: the multipart spool threshold can be set above the file-size limit so uploads never touch the filesystem, and resumability stops being a question worth asking. The ceiling was raised from the originally drafted 5 MB before implementation, because 5 MB rejects most *scanned* documents (commonly 10–20 MB), and a scanned contract is exactly what a prospect would upload to a contract-review demo. The failure mode being avoided is a prospect hitting a wall on their first realistic document during an evaluation.

**`google-cloud-storage` is used, a deliberate departure from the `RestClient` precedent.**
`poc-deployment-pipeline.md` chose a plain `RestClient` over the Cloud Build SDK to avoid pulling gRPC and a large dependency surface into an app with no GCP client libraries. That reasoning does not transfer. Cloud Build was a single JSON `POST` and a single `GET`, whereas GCS upload involves session handling, chunked transfer, and retry semantics — real logic rather than request construction, and logic that when hand-rolled tends to fail only under conditions that are hard to reproduce. The GCS client is HTTP/JSON by default, not gRPC, so the specific cost that motivated the pipeline's choice does not apply.

### Access control

**A POC derives its `(user, POC)` scope from its token and cannot supply either identifier.**
The POC-scoped token issued by `POST /pocs/{slug}/launch` (see `poc-hosting-architecture.md`) carries `sub` for the user and `aud: poc:<slug>` for the POC. The POC-facing endpoints read both from the token and accept no user or POC parameter at all. A POC therefore has nothing to tamper with: requesting another user's files is not a request the API can express, rather than a request it rejects. This is why the POC-facing endpoints are a separate, parameterless surface rather than the portal-facing ones under different authorization.

### Lifecycle and purge

**Purge fires after a grace period following trial expiry, and pauses while an extension is pending.**
`trial.purge-grace-days` (default 30) is counted from `trialEndDate`. Access is already cut off at expiry itself by the existing `403` (`trial-access-enforcement.md`), so purge is a separate and later event, which leaves room for a user who converts in week three and avoids destroying data because of a weekend outage. The job skips any user with `pending_extension_requested_at IS NOT NULL` — `trial-extension-requests.md` stores pending requests as two nullable columns on `users` rather than a separate table — since destroying the data of someone actively asking for more time would be indefensible. If an extension is granted, `trialEndDate` moves and the grace period recomputes with no special handling.

**The purge trigger is a scheduled sweep that claims users with `FOR UPDATE SKIP LOCKED`.**
Nothing in the platform currently reacts to a trial ending; `trial-access-enforcement.md` implements expiry purely as a `403` derived from a token claim, with no job, event, or lifecycle hook. The trigger therefore has to be built here, alongside the thing it triggers.

It runs on a daily cron (`trial.purge-cron`) rather than the short `fixedDelay` used by `DeploymentStatusPoller`: that poller watches builds resolving in minutes, whereas this watches a boundary that moves once a day, and a tight loop would repeatedly scan for work that cannot have appeared. Eligibility is `trial_end_date + trial.purge-grace-days < now()`, with `pending_extension_requested_at IS NULL` and `purged_at IS NULL`.

`self-service-api` may run on more than one Cloud Run instance, and every instance runs its own scheduler, so two can fire the same sweep concurrently. Users are claimed with `SELECT … FOR UPDATE SKIP LOCKED` in bounded batches — the same pattern `poc-deploy-pipeline` uses to claim `deployment_jobs`, chosen so this codebase has one concurrency idiom rather than two.

**The purge job is idempotent and resumable, and deletes objects before rows.**
Deleting a user's objects is not one operation but many, and a failure partway through is expected rather than exceptional. A partial run leaves a consistent, smaller set of files and completes on the next tick; completion is recorded on the user so a rerun is cheap. Ordering matters: an orphaned row pointing at a deleted object is a harmless inconsistency, while an orphaned object with no row is data the platform has lost track of and can no longer purge. Within a claimed user the order is GCS objects, then `user_files` rows, then Tier 1 state, then the Tier 2 sweep, then `purged_at`.

**Purge requires alerting, because its failure mode is silence.**
A purge job that stops running raises no error; data simply accumulates, and the system looks healthy. Per-user deletion counts are logged, and an alert fires when eligible users exist but no purge has completed within an expected interval. Without that, this feature working and this feature being broken are indistinguishable from the outside.

**This job assumes no paid or converted account state exists.**
`users` carries `status` (`ACTIVE` / `PENDING_VERIFICATION`) and trial dates; nothing represents a paying customer. Purging on trial expiry is correct only while every account is a trial. If conversion is ever introduced, this job must be updated in the same change, or it will begin deleting paying customers' data on a thirty-day delay. Stated explicitly because the failure would be silent, delayed, and unrecoverable.

### Malware

**No active scanning; the risk is bounded structurally instead.**
The canonical file-upload risk is the platform becoming a distribution channel — one user uploads something malicious and it is served to others. That vector does not exist here: files are scoped to a single `(user, POC)` pair and are never readable by another user, so nothing uploaded can reach anyone else. What remains is a malformed file exploiting a parsing library inside a POC, which scanning addresses only partially in any case.

The mitigations are a content-type allowlist validated against magic bytes rather than the client-declared header, downloads always served as `Content-Disposition: attachment` with `nosniff` so nothing can execute in a browser origin, and parser hardening — timeouts, memory bounds, no shell-outs — as an item in the POC template.

This decision depends entirely on the absence of that distribution vector, so it must be revisited if file scoping ever becomes shared between users, if files are served inline in a browser context, or if the portal gains a sharing feature. The likely implementation then is ClamAV on Cloud Run scanning the object after upload, with a scan state on the row gating readability. A third-party scanning service is a poor fit, since it would mean sending prospects' confidential documents outside the platform.

## Data Model

**`user_files`** — migration `V18__create_user_files_table.sql`. The original draft named this `V17`; `V17__rename_pocs_status_to_visibility_status.sql` landed first, so the number moved.

| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | matches the `pocs` convention |
| user_id | TEXT NOT NULL | ULID, matching `users.id` |
| poc_id | BIGINT NOT NULL | plain FK column, not a JPA relation — matches `ActivitySession.pocId` and `poc_deployments.poc_id` |
| object_name | TEXT NOT NULL UNIQUE | full GCS object path, `users/<userId>/pocs/<pocId>/<fileId>` |
| original_filename | TEXT NOT NULL | as uploaded; never part of `object_name` |
| content_type | TEXT NOT NULL | client-declared, stored but not trusted |
| size_bytes | BIGINT NOT NULL | |
| uploaded_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |
| deleted_at | TIMESTAMPTZ | nullable; soft delete, matching the `pocs` convention |
| created_at / updated_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |

Indexes: `(user_id, poc_id) WHERE deleted_at IS NULL` for the listing query, and `(user_id)` for the purge sweep.

**`users`** gains `purged_at TIMESTAMPTZ` (nullable) so a completed purge is not repeated.

## API Surface

Two surfaces, deliberately not merged: they differ in caller, in authorization, and in whether the scope is a parameter or a claim.

**POC-facing** — the primary surface, authenticated with the POC-scoped token, with no user or POC parameter anywhere. Called from the POC's browser code via the bridge library's uploader, and from the POC's backend for reads.

- **`POST /poc-files`** — `multipart/form-data`, single file. Rejects on quota (`409`), size (`413`),
  or disallowed content type (`400`). Returns `201` with the file's metadata.
- **`GET /poc-files`** — the non-deleted files belonging to the `(user, POC)` pair named by the token's claims.
- **`GET /poc-files/{fileId}/content`** — streams the bytes, after confirming the file belongs to that pair.
- **`DELETE /poc-files/{fileId}`** — soft delete, `204`.

**Portal-facing**, authenticated with the user's own access token, trial-gated as normal. Not part of the upload flow; this exists so the portal can offer the user a view of everything they have stored and a way to remove it, which is worth having independently of any POC.

- **`GET /pocs/{id}/files`** — the caller's non-deleted files for that POC.
- **`GET /pocs/{id}/files/{fileId}/content`** — streams the bytes.
- **`DELETE /pocs/{id}/files/{fileId}`** — soft delete, `204`.

The surfaces stay separate rather than merging behind one path with two authorization rules, for the reason given under Access control: the POC-facing endpoints must have no parameter naming a user or a POC, so cross-tenant access is a request the API cannot express rather than one it rejects.

## Security Considerations

- The uploaded filename is never used to construct an object path, a local path, or a header value without escaping. See the object-naming decision for why this matters beyond traversal.
- `content_type` is client-declared and not trusted. Downloads are served with `X-Content-Type-Options: nosniff` and `Content-Disposition: attachment`, so a user-uploaded HTML or SVG file cannot execute in a browser origin.
- The bucket is private, with uniform bucket-level access and no public objects. Access is exclusively through this API.
- Cross-tenant access is structurally prevented rather than checked: the POC-facing endpoints have no parameter that names a user or a POC.
- Purge covers GCS objects and database rows together. `poc-hosting-architecture.md` records why the database is not exempt despite holding far less data.
- No malware scanning is performed; see the Malware decision for the reasoning and the conditions that would reverse it.
- **Operational note.** Cloud Run's filesystem is memory-backed, so Spring's multipart spooling to a temporary file counts against container memory rather than disk. At 10 MB the sound configuration is to set the spool threshold above the file-size limit so uploads never touch the filesystem, and to size container memory for `10 MB × max concurrency` with headroom. `spring.servlet.multipart.max-file-size`, the spool threshold, container memory, and max concurrency should be set as one coherent decision rather than independently.

## Implementation Plan

Five phases, each independently reviewable and each leaving the build green. Per this repo's
spec-first convention, the spec revision for a phase is committed before that phase's code.

### The prerequisite this feature has to carry

`poc-hosting-architecture.md` specifies `POST /pocs/{slug}/launch` and `GET /.well-known/jwks.json`,
and neither exists: `JwtService` issues only user access tokens, with no `aud` and no `kid`, and
`SecurityConfig` has no JWKS route. Because upload lives *only* on the POC-facing surface — see the
Scope and ownership decision above — file management has no authenticated caller at all without
them, and could not be exercised end to end. That work is therefore built here, as Phase 2, rather
than waiting on a separate branch, and `poc-hosting-architecture.md` is revised in the same change.

Issuing a second class of token also opens a gap that must close in the same phase rather than
after it: nothing validates `aud` today, so a POC-scoped token would satisfy `anyRequest()
.authenticated()` and reach portal endpoints such as `/users/me`. Audience separation is part of
Phase 2, not a follow-up to it.

### Phase 1 — Storage foundation (implemented)

No HTTP surface; nothing user-visible.

- `pom.xml` — `com.google.cloud:google-cloud-storage`, pinned through `libraries-bom` so it aligns
  with the `google-auth-library-oauth2-http` already present for the deploy pipeline rather than
  resolving against it. HTTP/JSON transport, no gRPC, per the dependency decision above.
- `V18__create_user_files_table.sql` — the `user_files` table as specified under Data Model.
- `file/entity/UserFile.java`, `file/repository/UserFileRepository.java` — the `poc` package's
  conventions: identity PK, `deleted_at` soft delete, `poc_id` as a plain column not a JPA relation.
- `file/config/FileStorageProperties.java` — bucket, per-file ceiling, per-pair file count, per-user
  byte total, content-type allowlist.
- `file/storage/FileStorage.java` with `GcsFileStorage` and `LocalFileStorage` implementations,
  selected by property. This mirrors the `PipelineExecutor` local/cloud/skip precedent, and exists
  for the same reason: the feature stays runnable by a developer with no GCP credentials.
- `file/service/ContentTypeValidator.java` — magic-byte sniffing, since the declared header is not
  trusted. Under `service` rather than `storage`: it validates a request, and storage never calls it.
- `file/storage/ObjectPaths.java` — the object layout in one place, because upload, download and
  purge all have to agree on it.

### Phase 2 — POC-scoped token and JWKS (implemented)

Prerequisite work owned by `poc-hosting-architecture.md`; see above for why it lands here.

- `JwtService` — a `kid` header on every issued token, and `issuePocToken` minting the short-lived
  `aud: poc:<slug>` token.
- `GET /.well-known/jwks.json` — the existing `RSAPublicKey` bean exported public-key-only through
  Nimbus, which is already on the classpath via the resource server. Added to `SecurityConfig`'s
  `permitAll()` matchers, not merely marked `security: []` in OpenAPI — `poc-catalog.md` records
  that exact mismatch causing a failure before.
- `POST /pocs/{slug}/launch` — the single point where "may this user open this POC" is decided:
  active trial, POC `ACTIVE`, not hidden, not soft-deleted.
- Audience separation. Implemented as the half that is safe to ship first: the portal's decoder
  *rejects* any token carrying a `poc:` audience, so a POC-scoped token is currently accepted
  nowhere at all. Phase 3 adds the decoder that requires it, opening exactly one door. Checked at
  decode time rather than as an authorization rule, so such a token fails to authenticate rather
  than authenticating and then being denied.

### Phase 3 — POC-facing `/poc-files` (implemented)

- OpenAPI paths and a `file.yaml` schema component; the controller implements the generated
  interface, as every controller in this repo does.
- `POST` (multipart), `GET`, `GET /{fileId}/content`, `DELETE` — no user or POC parameter anywhere,
  both read from token claims. Status codes settled beyond what the API Surface section originally
  said: `413` for the per-file size ceiling, `409` for either quota (file count or total bytes),
  `400` for a rejected or mismatched content type. `413`/`409` split rather than folding both into
  `400` because they are different questions — one about the request just made, the other about the
  caller's existing state — and a client distinguishing "this file is too big" from "you're out of
  room" needs the codes to say so.
- `FileService` — quota enforcement, content-type validation, object naming, soft delete.
- Multipart configuration in `application.yaml`, with the spool threshold set above the file-size
  limit so uploads never touch Cloud Run's memory-backed filesystem.
- Error mapping through `ApiException`, matching the existing `poc` exception package.

### Phase 4 — Portal-facing `/pocs/{id}/files` (implemented)

Read and delete only, on the user's own access token, trial-gated as normal. Reuses the Phase 3
`FileService` unchanged: every method there already scopes strictly by `(userId, pocId)`, so an id
belonging to another user or a different POC is simply not found, which is exactly the ownership
check this surface needs — no new logic, only a second controller reading the pair from
`CurrentUser` and the path instead of the token's claims. The path parameter is `id`, matching
every other `/pocs/{id}/...` route in this API, not the `pocId` the API Surface section below uses
in prose; the two names the same thing.

### Phase 5 — Trial-expiry purge

- `users.purged_at` (migration), `trial.purge-*` properties.
- `@EnableScheduling` — the first scheduled work in this service. `AsyncConfig` enables `@Async` for
  the deploy pipeline, but nothing here is scheduled today, and the `FOR UPDATE SKIP LOCKED` claim
  pattern the design borrows lives in the `poc-deploy-pipeline` repo, not this one. It is written
  here rather than reused.
- `TrialPurgeService` — claims eligible users in bounded batches, deletes objects before rows,
  skips users with a pending extension, records `purged_at`, and is safe to interrupt at any point.
- Per-user deletion counts logged and a staleness signal exposed, because a purge job that stops
  running raises no error on its own.

## Open Questions / Future Work

- ~~**Is 5 MB the right ceiling?**~~ Resolved 2026-09-03: raised to 10 MB before implementation, for the reason the question itself gave. See the quota decision above.
- ~~**Which content types are on the allowlist?**~~ Resolved 2026-09-03: PDF, DOCX, XLSX, PPTX, TXT, CSV, PNG and JPEG, as proposed — the set that covers the document POCs described so far. It is a configuration property, but a narrowing one: it selects from the types `ContentTypeValidator` holds a content signature for, and naming one it does not fails startup. Anything else would mean skipping validation for that type, which quietly turns the allowlist back into a claim from the client — the thing it exists to replace. So removing a type is configuration; adding a genuinely new one is a signature, and therefore a release.
- **Should users be warned before purge?** `transactional-email.md` and `email-templates.md` already provide the machinery, and "your trial data will be deleted in seven days" is both good practice and a conversion prompt. Purely additive to this design.
- ~~**Which bucket?**~~ Resolved 2026-09-03: a separate, dedicated bucket, named by the `files.bucket` property (`FILE_STORAGE_BUCKET`). Retention and access are the whole point of this bucket and are entirely unlike the build artifacts sharing the pipeline's, so keeping them apart costs one resource and buys a lifecycle policy that can be reasoned about on its own. The bucket-level IAM binding this needs is the one category already demonstrated to succeed in this project (see the transport decision).
- **What happens to files when a POC is soft-deleted?** The `(user, POC)` scoping means those files become unreachable but are not purged, since purge is keyed on the user's trial rather than the POC's existence. Probably wants a sweep, but it is a genuinely separate lifecycle.
- **GCS lifecycle rules as a backstop.** An age-based rule would not implement the trial-scoped purge policy, but it would bound the damage if the purge job silently stopped running — a cheap second line of defence.
- **Resumable uploads.** The proxy design has none; a dropped upload restarts. Acceptable at 10 MB, and inherited from the signed-URL decision rather than independent of it.
- **Per-POC quota overrides.** A flat limit across all POCs will eventually be wrong for one of them.

## Changelog

- 2026-09-03 — **Phase 4 implemented**: `/pocs/{id}/files` (list, download, delete), on the
  portal's existing filter chain — no `SecurityConfig` change needed, since the path falls through
  to the same `anyRequest().access(authenticated + trial)` rule every other portal route already
  gets. `PortalFilesController` is a thin second controller over the unchanged Phase 3
  `FileService`; `SecurityConfigTest` gained the matching pair of cases (a POC-scoped token
  rejected here, a portal token accepted) to prove the new surface actually stayed on the portal
  chain rather than assuming it from the routing table. 213 tests pass, 8 new.
- 2026-09-03 — **Phase 3 implemented**: `/poc-files` (upload, list, download, delete), sitting
  behind a second Spring Security filter chain (`SecurityConfig.pocFilesFilterChain`, `@Order(1)`,
  matched to `/poc-files/**`) rather than a shared one with an authorization-time check. The two
  chains are the actual isolation — a portal access token cannot authenticate against
  `/poc-files/**` at all, and a POC-scoped token cannot authenticate against anything else — with
  `RequirePocAudienceValidator` (the mirror of `PortalAudienceValidator` from Phase 2) as the
  decoder-level rule that decides which chain accepts which token. `CurrentPoc`, alongside the
  existing `CurrentUser`, reads `pocId` off the token so the controller never takes a POC parameter.
  Delete removes the object immediately (freeing quota right away) and only then soft-deletes the
  row, so the row's `deleted_at` is purge accounting, not a recycle bin — matching what Phase 1's
  `UserFile` Javadoc already said before this phase existed to implement it. Multipart limits
  (`MultipartUploadConfig`) are derived from `files.max-file-size` in code rather than set
  independently in YAML, which is what guarantees the spool threshold stays above the file-size
  ceiling for whatever that property is configured to, rather than two numbers that happen to
  agree today. `SecurityConfigTest` gained the mirror-image cases Phase 2 didn't yet need: a portal
  token rejected on `/poc-files`, and a POC token accepted there. `MultipartUploadConfigTest` exists because `@WebMvcTest`'s mock dispatcher never touches a real `MultipartConfigElement` — without it, the file whose entire purpose is the spool-threshold guarantee would be untested. 205 tests pass, 30 new.
- 2026-09-03 — **Phase 2 implemented**: `kid` on every issued token, `GET /.well-known/jwks.json`,
  `POST /pocs/{slug}/launch`, and audience separation. The POC token carries `sub`, `aud`,
  `pocId`, display name, theme and `trialEndDate` — and deliberately no `roles`, so a POC launched
  by an admin does not inherit admin authority, and no email. `pocId` is carried in addition to the
  slug in the audience, which the design did not call for: file storage is keyed on the id
  precisely because a slug can be renamed, so carrying it saves a lookup on every request a POC
  makes. `SecurityConfigTest` is this repo's first test of its security wiring, and covers the case
  that motivated the phase — a POC-scoped token getting 401 on `/users/me` while a user token
  passes the filter. See `poc-hosting-architecture.md` for the launch endpoint's own details.
- 2026-09-03 — **Phase 1 implemented**: `google-cloud-storage` (pinned through `libraries-bom`,
  which also took over the version of the `google-auth-library-oauth2-http` the deploy pipeline
  already used, so the two cannot drift), migration `V18`, `UserFile` + `UserFileRepository`,
  `ObjectPaths`, the `FileStorage` interface with GCS and local implementations, and
  `ContentTypeValidator`. Two things the design did not anticipate. First, `user_files.user_id` is
  `VARCHAR(36) REFERENCES users(id)` rather than the specified bare `TEXT`, matching
  `activity_sessions` — but deliberately *without* that table's `ON DELETE CASCADE`, since cascading
  would let a deleted user take these rows with them while their objects stayed in the bucket, which
  is precisely the orphan this design orders its purge steps to avoid. Second, the allowlist turned
  out to narrow rather than widen; see the resolved open question above. Verified against the real
  local Postgres: Flyway applied `V18` and revalidated all 18 migrations, and Hibernate's
  `ddl-auto: validate` accepted the mapping. 139 tests pass, 23 of them new.
- 2026-09-03 — Implementation started. Three decisions taken off the Open Questions list before
  writing code: the per-file ceiling raised from 5 MB to 10 MB (5 MB rejects the scanned documents a
  contract-review prospect would actually upload, and 10 MB keeps every simplification that made
  5 MB attractive); the content-type allowlist fixed at the proposed set, as configuration rather
  than a constant; and user files given their own bucket rather than a prefix in the pipeline's,
  since retention and access are the entire point of this bucket and are unlike the build artifacts
  it would otherwise share. Corrected the migration number to `V18` — the draft said `V17`, which
  `V17__rename_pocs_status_to_visibility_status.sql` has since taken. Added the Implementation Plan,
  which folds `poc-hosting-architecture.md`'s launch token and JWKS endpoint in as Phase 2, on the
  grounds that this feature's only upload path is authenticated by a token that does not yet exist.
- 2026-09-02 — Initial draft.

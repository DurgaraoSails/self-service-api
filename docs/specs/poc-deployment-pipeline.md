# POC Deployment Pipeline

## Status

In Progress

## Overview / Purpose

Turns "add a POC" from a manual, fully-admin-typed catalog entry (see `docs/specs/poc-catalog.md`) into a self-service deploy: the admin supplies a name, a slug, and a GitHub repo URL, and the API tags the repo's `main` branch, builds a container image, pushes it to Artifact Registry, and deploys it to Cloud Run — all without the admin touching `gcloud` or Cloud Build directly. Supersedes the original implementation plan's assumption (`poc-portal-implementation-plan.md`, Part 10) that teams push to `main` and a pre-wired Cloud Build GitHub-App trigger fires automatically; the actual requirement is admin-triggered and on-demand — nothing fires on a raw `git push`, and the build clones the repo itself using a token rather than a stored per-repo trigger.

## Requirements

- Admin adds a POC in the portal, providing repo details including the GitHub URL; this both creates the `pocs` row and kicks off the first deploy.
- The create call returns `201` immediately with the POC's details — the pipeline (tag → build → push → deploy) must run asynchronously and never block the request.
- A POC must not appear in the regular dashboard/catalog listing until its deployment has actually succeeded — not merely been submitted.
- The tag version and the Artifact Registry image reference for every deploy attempt must be persisted, so the admin UI can show current/past releases and an admin can track what's actually live.
- Failures anywhere in the pipeline must be recorded with a reason, not silently swallowed.
- An existing POC can be redeployed at a new version on demand (`deploy-new-version`), with an explicit or default (minor) semver bump.
- An admin can ask "is there an update available" by comparing the repo's current `main` HEAD against what's actually deployed.

## Architecture Decisions

**`deployment_status` is a new column on `pocs`, deliberately separate from the existing `status`.**
`status` (`ACTIVE`/`HIDDEN`, see `poc-catalog.md`) is an admin visibility toggle, unrelated to whether a deploy succeeded. Reusing it for pipeline lifecycle (`not_deployed`/`building`/`active`/`failed`) would make `hide()`/`unhide()` and a failed build stomp on each other. Existing rows default to `deployment_status = 'active'` so the catalog's pre-pipeline seeded POCs (`V7__seed_initial_pocs.sql`) keep showing up unchanged; the pipeline-triggered create path explicitly overrides this to `not_deployed`.

**`poc_deployments` is a genuinely new table, not an alteration of an existing one.**
The implementation plan's schema section only showed `ALTER TABLE poc_deployments ADD COLUMN ...`, asserting the table "already exists per the design doc §5.5/§8" — it didn't, in this codebase. `V14__create_poc_deployments_table.sql` creates it from scratch: one row per tag-build-deploy attempt (`poc_id`, `release_tag`, `commit_sha`, `cloud_build_id`, `image_uri`, `status`, `failure_reason`, `triggered_by`, timestamps).

**Release tags are bare semver (`1.2.0`), never `v`-prefixed.**
The same string is reused, unmodified, as the Artifact Registry image tag (`poc-images/<slug>/app:<tag>`). One canonical version string end to end avoids stripping/adding a `v` prefix at each pipeline stage.

**`GitHubService` resolves the default branch dynamically; it's never hardcoded to `main`.**
The implementation plan assumed `main`. The actual test repo (`dummy-poc`, see Open Questions) came up as `master` from `gh repo create` until manually renamed — proof that hardcoding would have silently misbehaved on any repo that isn't `main`.

**Cloud Build is called via plain `RestClient` + `google-auth-library-oauth2-http`, not the full `google-cloud-build` SDK.**
This app has zero GCP client libraries today — Secret Manager access is a mounted file, not an API call (see `JwtKeyConfig`). Adding the full Cloud Build SDK would pull in gRPC and a large dependency surface for what's a single REST endpoint (`projects.builds.create` / `.get`). A `RestClient` bean authenticated via an `Application Default Credentials`-backed interceptor matches the existing `brevoRestClient`/`gitHubRestClient` pattern in `RestClientConfig` exactly.

**Docker Hub is dropped from the build entirely.**
The plan's original Cloud Build template pushed every image to both Docker Hub and Artifact Registry (a "backup copy"). Nothing in the actual requirements reads from Docker Hub, and it costs two extra secrets (`DOCKERHUB_USERNAME`/`PASSWORD`) and a login step for no benefit. `self-service-terraform` still declares the Docker Hub secret resources (unused) — left in place deliberately rather than edited under deadline pressure; see Open Questions.

**The GitHub token is injected via Secret Manager `secretEnv`, never as a literal substitution in build `args`.**
The plan's template built the git-clone URL as a Cloud Build substitution (`${_REPO_URL_WITH_TOKEN}`) used directly in a step's `args`. Cloud Build permanently stores the *resolved* `args` on the Build resource — anyone with build-read access on the project could read the token out of build history forever. `BuildService` instead references `availableSecrets.secretManager` (a `versionName`, never a literal value) and the clone step runs as `bash -c "git clone ... https://x-access-token:$$GITHUB_TOKEN@..."`, with `$$GITHUB_TOKEN` resolved as a runtime env var Cloud Build injects — the literal secret value is never part of any API request/response body this app constructs. This requires `self-service-builder` to hold `secretAccessor` on the `github-token` secret *in addition to* `self-service-api`'s own accessor grant (used separately, to create tags via the GitHub API) — a binding the original plan's IAM design didn't anticipate, since it assumed a stored substitution instead.

**`options.logging: CLOUD_LOGGING_ONLY` is required and was missing from the plan's template.**
The Cloud Build API rejects any build specifying a custom `serviceAccount` unless a non-default logging option is also set. Confirmed for real: a submitted build without it would have been rejected outright; a real build submitted *with* it surfaced a second, previously-unknown-to-the-plan requirement — the builder identity also needs `roles/logging.logWriter`, or Cloud Build accepts the build but can't record its own logs (see Changelog, 2026-09-01).

**`DeploymentOrchestrator` only submits; `DeploymentStatusPoller` (scheduled, not event-driven) confirms outcomes.**
Submitting a build and it *finishing* are minutes apart — far longer than an HTTP request should wait. `DeploymentOrchestrator.triggerInitialDeployment`/`triggerNewVersion` are `@Async` (Spring's default executor, `@EnableAsync` in `AsyncConfig`; no message queue) and return once a build has been *submitted*, not resolved. `DeploymentStatusPoller` (`@Scheduled(fixedDelayString = "${deployment.poll-interval-ms:15000}")`, `SchedulingConfig`) separately polls every `building` `poc_deployments` row every 15s by default and is the **only** place `pocs.current_release_tag` ever gets set — "submitted" is deliberately never conflated with "live". The poller also distinguishes a transient polling error (network blip — leave the row `building`, retry next tick) from an actual terminal failure reported by Cloud Build, so a momentary API hiccup can't mark a healthy in-flight build as failed.

**The `poc_deployments` row is persisted with a placeholder `release_tag` before anything that can fail.**
`release_tag` is `NOT NULL`. Building the row's real fields only inside the pipeline's `try` block means a failure *before* computing the tag (e.g. a malformed repo URL) would have nothing valid to insert when the `catch` block tries to record the failure — a secondary insert failure masking the original error. `DeploymentOrchestrator.runPipeline` inserts the row up front with `release_tag = "pending"`, so a row (and thus visible pipeline progress) always exists regardless of where things break.

**No single `@Transactional` spans the whole pipeline run.**
`runPipeline` interleaves slow external calls (GitHub API, Cloud Build API) with DB writes; each write is its own `repository.save()` call rather than one wrapping transaction. The Hikari pool here is sized to 5 connections (`application.yaml`) — holding one open across multi-second network round trips for an admin-triggered, low-frequency operation isn't worth the risk.

**`check-updates` compares against the *active* deployment's commit, not the *last attempted* one.**
The plan specified comparing `latest_main_commit_sha` against a `last_deployed_sha` column that, like `poc_deployments` itself, was assumed to already exist and didn't. Fixed by adding `poc_deployments.commit_sha` (`V15`, the commit `main` HEAD pointed at when that deployment's tag was created) and defining "what's deployed" as the `commit_sha` of the most recent deployment with `status = 'active'` — not whatever the most recent attempt used, successful or not. A failed redeploy attempt must never read as "already up to date".

**`POST /pocs` now has exactly one behavior: create always triggers the pipeline.**
The pre-existing manual-entry flow (admin types every field, including `appUrl`/`containerImage`, directly — see `poc-catalog.md`) is retired as of this feature. `slug` and `githubUrl` are now required (`@NotNull`/`@Pattern`, generated from the OpenAPI schema); `appUrl`/`containerImage` become pipeline-derived rather than admin-supplied. `PocService.createForPipeline` is the sole creation path going forward, always setting `deployment_status = "not_deployed"` regardless of what the (now-optional-and-effectively-unused-for-this-purpose) `status` field is set to.

**The fleet view is one filtered endpoint, not a "build status" endpoint plus a "failed POCs" endpoint.**
Both admin screens want the same thing — one row per POC carrying only its latest attempt — and differ solely in whether the set is filtered to `failed`. Two endpoints would duplicate the "latest per POC" logic and drift. `GET /pocs/deployments/latest?status=` covers both. It also fills a hole the catalog filter below opened: with `GET /pocs` hiding everything not live, this is the *only* way an admin can see a POC that is building or failed.

**"Update available" needs no separate endpoint, and no version input from the admin.**
The admin never supplies a tag: `deploy-new-version` with no body bumps the minor version off whatever tags already exist. So the UI's "Update" button is just `check-updates` (to decide whether to offer it) followed by an unparameterized `deploy-new-version`. The optional `bump` field stays in the contract for a future power-user path, but nothing in the intended flow sends it. One consequence worth knowing: `updateAvailable: false` is returned both when a POC is genuinely up to date and when nothing has ever gone live — the UI distinguishes them via `deployedCommitSha == null`, since "never deployed" should offer *Deploy*, not *Update*.

**The dashboard/catalog listing excludes anything not `deployment_status = "active"`, for admins too.**
`PocService.listForViewer` filters on this uniformly rather than only for non-admins — the requirement was that an in-progress or failed POC never clutters the main catalog, and the dedicated `deployments/latest` endpoint is the intended way to watch one in progress, reached directly by the id/slug the create call returned rather than via the list.

**The Cloud Build `RestClient` uses `SimpleClientHttpRequestFactory`, not Spring's default JDK `HttpClient`.**
Discovered while testing for real: the default JDK `HttpClient` request factory needs a local loopback socket for its async selector, which failed with `Unable to establish loopback connection` in the sandboxed dev environment used to build this feature — a known class of failure in sandboxed/restricted-network setups (and, less predictably, some corporate VPN/proxy configurations on real machines). `SimpleClientHttpRequestFactory` (blocking I/O, no selector) sidesteps it entirely; there's no meaningful downside for the low-frequency, non-streaming calls this app makes to Cloud Build.

## Data Model

**`pocs`** additions (migration `V13__add_deployment_fields_to_pocs.sql`, entity `poc/entity/Poc.java`)
| column | type | notes |
|---|---|---|
| slug | TEXT, UNIQUE | nullable (pre-pipeline catalog rows have none); Cloud Run service name / image path segment |
| deployment_status | VARCHAR(50) NOT NULL DEFAULT 'active' | `not_deployed` \| `building` \| `active` \| `failed`; independent of the existing `status` column |
| current_release_tag | TEXT | nullable; set only by `DeploymentStatusPoller` once a deploy is confirmed live |
| latest_main_commit_sha | TEXT | nullable; last-observed `main` HEAD (written by both the orchestrator and `check-updates`) |
| latest_main_checked_at | TIMESTAMPTZ | nullable; when `latest_main_commit_sha` was last refreshed |

**`poc_deployments`** (migration `V14__create_poc_deployments_table.sql`, `V15__add_commit_sha_to_poc_deployments.sql`; entity `deployment/entity/PocDeployment.java`) — one row per tag-build-deploy attempt
| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| poc_id | BIGINT NOT NULL | plain FK column, not a JPA relation — matches the existing `ActivitySession.pocId` convention |
| release_tag | TEXT NOT NULL | `"pending"` until the real version is computed (see Architecture Decisions) |
| commit_sha | TEXT | nullable; `main` HEAD this deployment's tag was created from — what `check-updates` compares against |
| cloud_build_id | TEXT | nullable until the build is actually submitted |
| image_uri | TEXT | nullable; Artifact Registry reference, once pushed |
| status | VARCHAR(50) NOT NULL DEFAULT 'building' | `building` \| `active` \| `failed` |
| failure_reason | TEXT | nullable; populated from either a caught exception or Cloud Build's own `failureInfo.detail` |
| triggered_by | TEXT | nullable; admin user id from `CurrentUser.id()` |
| started_at / finished_at | TIMESTAMPTZ | `finished_at` null while still `building` |
| created_at / updated_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |

Indexes: `poc_deployments_poc_id_idx`, and a partial `poc_deployments_in_flight_idx ON (status) WHERE status = 'building'` for the poller's scan query.

## API Surface

All new/changed endpoints live under `/pocs`, same `PocApi`-generating tag as `poc-catalog.md`.

- **`POST /pocs`** — **admin only**, behavior changed. `CreatePocRequest` now requires `slug` (pattern `^[a-z0-9]+(-[a-z0-9]+)*$`) and `githubUrl` in addition to `name`/`description`. Creates the row with `deploymentStatus = not_deployed`, calls `DeploymentOrchestrator.triggerInitialDeployment` (fire-and-forget), and returns `201` `PocResponse` immediately — well before the pipeline finishes. `PocResponse` gained `slug`, `deploymentStatus`, `currentReleaseTag`.
- **`POST /pocs/{slug}/check-updates`** — **admin only** (new). Re-fetches the repo's `main` HEAD live (no caching) and compares it to the active deployment's `commit_sha`. Returns `CheckUpdatesResponse {updateAvailable, latestMainCommitSha, deployedCommitSha}`; `deployedCommitSha` is `null` (and `updateAvailable` is `false`) if nothing has ever gone active.
- **`POST /pocs/{slug}/deploy-new-version`** — **admin only** (new). Optional body `DeployNewVersionRequest {bump: major|minor|patch}`, defaults to `minor` whether the field is omitted *or the whole body is omitted*. Calls `DeploymentOrchestrator.triggerNewVersion` asynchronously; returns `202` with no body.
- **`GET /pocs/{slug}/deployments/latest`** — **admin only** (new). Returns the most recent `poc_deployments` row regardless of its status (`building`/`active`/`failed`) — this, not `check-updates`, is what an admin-facing progress view polls. `404` (`PocDeploymentNotFoundException`) if the POC has no deployment recorded yet.
- **`GET /pocs/deployments/latest`** — **admin only** (new). One row per POC with only its most recent deployment attempt, newest activity first. Optional `?status=not_deployed|building|active|failed` filters on the POC's pipeline state — `?status=failed` is the "what's broken" view. Includes POCs that have never deployed (`latestDeployment: null`), excludes soft-deleted ones. Three path segments, so it never collides with the four-segment `/pocs/{slug}/deployments/latest`.
- **`GET /pocs`** — unchanged surface, changed *contents*: any POC with `deploymentStatus != "active"` is now excluded, for every caller including admins (see Architecture Decisions).

## Security Considerations

- The GitHub token is never present in any Cloud Build API request/response body this app constructs — see the `secretEnv` Architecture Decision above. It also never appears in this app's own logs (`BuildService`'s error-wrapping logs status codes and response bodies, not request bodies).
- `self-service-builder`'s Secret Manager access is scoped to exactly the one secret it needs (`github-token`), granted separately from `self-service-api`'s own accessor grant on the same secret — two different consumers, two explicit bindings, not one shared "the API's identity can read everything" grant.
- All four new/changed write-ish endpoints (`create`, `check-updates`, `deploy-new-version`, `deployments/latest`) are `@PreAuthorize("hasRole('ADMIN')")`. `deployments/latest` is a `GET` but still admin-gated — it can surface a `failureReason` string (effectively an internal error message), which regular users have no reason to see.
- The plan's POC-scoped file credential (`self-service-api` minting a long-lived JWT for a POC's own backend to call internal file endpoints) is **explicitly out of scope** for this pass — see Open Questions.
- Current real-world gap: the GCP project (`sails-agenthub`) IAM state has `self-service-builder`/`self-service-api`/`poc-runtime`/`self-service-gateway` service accounts and the shared Artifact Registry repo/GCS bucket/Secret Manager secrets created, but **five IAM bindings are still blocked** pending an IT-granted role (`roles/resourcemanager.projectIamAdmin`, `roles/iam.serviceAccountAdmin`, and/or `roles/secretmanager.admin` — the account applying Terraform currently holds only `roles/editor`, which deliberately excludes `setIamPolicy` everywhere except Cloud Storage's legacy ACL compatibility layer, which is why the bucket binding alone succeeded). All five are commented out in `self-service-terraform` (`iam.tf`, `secrets.tf`) with `BLOCKED (2026-09-01)` markers, ready to uncomment once granted. Until then, no POC can actually reach `deployment_status = active` for real — every piece of this feature has been verified up to and including a real Cloud Build submission, which fails at exactly the expected, already-diagnosed point (see Changelog).

## Open Questions / Future Work

- **Portal UI is entirely deferred**, by explicit priority order: the add-POC form needs a `slug` field (submitting today's form will `400` — it doesn't send one yet); a progress/status view for `deployments/latest` (with per-step estimated timing, an Artifact Registry link, and a release-tag history — explicitly asked for, not yet designed); an "update available" indicator wired to `check-updates`; a "Create New Version" bump-type picker wired to `deploy-new-version`; and surfacing `failureReason` on deploy failure.
- **`poc.appUrl` is never populated by this pipeline.** The deployed Cloud Run service is reachable only via the gateway's own service-account-to-service-account call (Parts 2/3/8/9 of the implementation plan — subdomain routing, DNS, load balancer), which this feature doesn't touch and isn't confirmed wired up in this environment. Until that's in place, a POC going `active` here means "the container is deployed and locked down," not "an end user can reach it at a URL."
- **The internal POC-scoped file API and `POC_FILE_TOKEN` minting** (plan §10, for a POC's own backend to manage its files via `self-service-api`) is not implemented. `BuildService`'s deploy step doesn't set any `POC_FILE_TOKEN` env var yet.
- **Single hardcoded image component (`app`).** `BuildService.imageUri` always builds `poc-images/<slug>/app:<tag>` — fine for every POC so far (single container), would need a `component` concept for a POC that splits frontend/backend into separate images.
- **The in-flight guard is a check, not a lock.** `deploy-new-version` rejects a second deploy while one is `building`, but two genuinely simultaneous requests could both pass the check before either writes. Acceptable here — it defends against the realistic case (an impatient double-click) rather than true concurrency, and the blast radius is a duplicate tag, not corruption.
- **Docker Hub Terraform resources are unused but not removed** (`secrets.tf`/`main.tf` still declare `dockerhub_username`/`dockerhub_password`) — placeholder values needed in `terraform.tfvars` to satisfy the required-variable check; real cleanup deferred past the deadline this feature was built under.
- **Polling vs. push for build status.** `DeploymentStatusPoller` polls every 15s; Cloud Build also publishes state changes to a `cloud-builds` Pub/Sub topic automatically, which could drive near-instant status updates via a push subscription instead. Not pursued — it's new infra (a subscription, an authenticated webhook endpoint) for a latency improvement that doesn't matter yet at this scale.
- **`deployment_status = active` has still never been reached**, because the IAM gap blocks the build at the same point every time. Everything up to that boundary is now verified against a live app and a real database (see Changelog, 2026-09-01 end-to-end run) — the remaining unverified surface is exactly: the build steps after `git clone`, the Cloud Run deploy, the gateway-invoker binding, and the poller's `SUCCESS` branch (its `FAILURE` branch is confirmed working against real Cloud Build output).
- **`deploy-new-version` with no request body at all** is the one code path not exercised live. `toBumpType(null)` returns `MINOR`; the body-present path (including an invalid enum → `400`) is confirmed. Left alone deliberately — each run creates a real git tag and a billable build, and it's a two-line branch.
- **The OpenAPI spec's `servers: - url: /api/v1` is not the runtime path.** The generator does not apply it as a mapping prefix and no `server.servlet.context-path` is configured, so every endpoint actually serves at the root (`/pocs`, not `/api/v1/pocs`). The Angular client already uses the root form, so nothing is broken — but the spec, and therefore Swagger UI's "Try it out", are misleading. Either drop the `servers` entry or set a matching context path.

## Changelog

- 2026-09-01 — Added the admin fleet view `GET /pocs/deployments/latest?status=`, closing a hole this feature had opened: because `GET /pocs` hides everything not live, an admin previously had no way to find a building or failed POC without already knowing its slug. One endpoint serves both the "latest build status of every POC" and "show me only the failures" screens. Also added an in-flight guard: `deploy-new-version` now returns `409 DEPLOYMENT_ALREADY_IN_PROGRESS` if a build for that POC is still `building`, since a double-clicked UI button would otherwise start two pipelines racing on the same status field. Confirmed no backend change was needed for the "admin clicks Update, pipeline picks the version" requirement — the existing no-body `deploy-new-version` already does exactly that.
- 2026-09-01 — **First live end-to-end run**, against the real Neon database with migrations `V13`–`V15` applied. Flyway validated all 15 migrations and Hibernate's `ddl-auto: validate` passed, confirming the `Poc`/`PocDeployment` mappings match the real schema. A real `POST /pocs` returned `201` immediately, the async pipeline created real GitHub tags (`1.0.0` → `1.1.0` → `1.2.0`, exercising both the initial deploy and `deploy-new-version`), submitted real Cloud Build jobs, and the poller correctly transitioned the row to `failed` with Cloud Build's own `secretmanager.versions.access` message as `failure_reason`. Verified live: the POC is excluded from `GET /pocs` even for admins; `current_release_tag` correctly stayed `null` (nothing reached active); `check-updates` returns `updateAvailable: false` with a null `deployedCommitSha` when no deployment has ever gone active; `404` on unknown slug, `401` unauthenticated, and `400` on both a missing and a malformed `slug`. **Fixed a defect found by this testing**: a duplicate `slug` surfaced the database unique-constraint violation as a bare `500`, instead of a usable error — `PocService.createForPipeline` now pre-checks and throws `PocSlugAlreadyExistsException` (`409`, matching the existing `UserAlreadyExistsException` pattern), and the `409` is documented on `POST /pocs`. The failed duplicate attempt was confirmed to leave no orphan row, tag, or build behind.
- 2026-09-01 — `BuildService` (submit + `getBuildStatus`), `DeploymentOrchestrator`, `DeploymentStatusPoller`, `CheckUpdatesService`, and the four API endpoints (`createPoc` rewritten, `checkPocUpdates`, `deployNewPocVersion`, `getLatestPocDeployment`) implemented and wired. Added `V15` (`poc_deployments.commit_sha`) once `check-updates`'s real comparison target turned out not to exist. Confirmed via a real Cloud Build submission that the pipeline's only remaining blocker is the IAM grant already tracked in Terraform; discovered and added a second missing binding (`roles/logging.logWriter` for `self-service-builder`) in the process.
- 2026-08-31 — Initial pipeline slice: migrations `V13`/`V14`, `GitHubService`, `VersionService`, `GcpProperties`, the `cloudBuildRestClient` bean. Stood up `dummy-poc` (github.com/DurgaraoSails/dummy-poc) as the disposable test target. Diagnosed the GCP project's Editor-role IAM gap (project/SA/secret `setIamPolicy` all blocked; bucket-level succeeded via Cloud Storage's legacy ACL carve-out) and proved the build/push/deploy mechanics work end to end via a manual, un-orchestrated Cloud Build submission — the deployed Cloud Run service came up correctly and correctly rejected unauthenticated access; only the final gateway-invoker IAM binding failed, for the same already-diagnosed reason.

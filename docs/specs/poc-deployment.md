# POC Auto-Deploy & Versioning

## Status

Implemented

## Overview / Purpose

Adding/editing a POC has always been just metadata — Save writes fields, nothing more. This
feature adds a real deploy pipeline integration: an admin can trigger a build of a POC's container
image from its `githubUrl` and a deploy to GCP Cloud Run, track every version ever built, roll back
to an older version by redeploying its already-built image, and see live deployment status.

**The actual build/deploy pipeline is out of scope here** — a separate team builds it, in the same
repo, and its trigger mechanism wasn't decided at the time this shipped. This feature builds the
tracking/versioning/UI layer plus a webhook contract (`POST /pocs/deployments/{deploymentId}/status`)
that pipeline will be built against. `self-service-api` never calls GCP directly and holds no GCP
credentials.

## Requirements

- An admin can trigger "Deploy new version" for a POC with a `githubUrl` set; this allocates a new
  version number and kicks off a build+deploy.
- An admin can roll back to any previously-built version; this redeploys that version's already-built
  image, without rebuilding.
- Versions are numbered automatically — `MAJOR.MINOR.PATCH`, no manual entry anywhere.
- Deployment status (and a link to logs, once the pipeline reports one) is visible while a deployment
  is in progress and afterward, in a "Deployment History" view.
- Regular Save (editing name/description/icon/etc.) never triggers a build — only the explicit deploy
  action does.

## Architecture Decisions

**A new, separate `PocDeploymentService`/`poc_versions`+`poc_deployments` pair, not folded into
`PocService`/`pocs`.** Versioning and deployment tracking is a distinct concern with its own
lifecycle from POC metadata CRUD — the same reasoning that already split `PocFields` out on its own
once POC CRUD grew past a comfortable size.

**Two tables, not one.** `poc_versions` (immutable once `container_image` is set — one row per
allocated number, whether or not the build succeeded) and `poc_deployments` (one row per deploy
*attempt* — a fresh build or a rollback redeploy — mutable, tracks lifecycle status). Rollback
redeploys an *existing* version row rather than allocating a new one, so a version and its deploy
attempts are genuinely many-to-one (rolling back to the same version twice is two deployment rows
against one version row) — one table can't represent both without duplicating version metadata
across rows.

**Versioning scheme: `MAJOR.MINOR.PATCH`, patch runs 1–20 per major.minor, hitting 20 rolls minor up
and resets patch to 1. Major never changes automatically — every version is `1.x.x`.** First-ever
deploy is `1.0.1`. Rollback never allocates a version number (it reuses the target's existing
number+image); a failed build's number is never reused (the next deploy always allocates the next
number). `major` is still its own column so a future manual-major-bump control would be a pure
addition, no migration.

**`pocs.version` and `pocs.container_image` (free-text, unvalidated, single-writer-only) are
removed entirely, replaced by `pocs.active_version_id` (nullable FK to `poc_versions`).** Mirrors
`status`'s own precedent in `poc-catalog.md`: once a field gets a real, structured owner, keep the
free-text version around only invites drift between the two. `active_version_id` is written in
exactly one place — the status-callback handler, only on a deployment reaching `SUCCEEDED` (both a
fresh build and a rollback flip it).

**Reuses the Contact-Sales-era config pattern for the webhook secret** (`@ConfigurationProperties`
record, env var with a local-dev default) rather than a DB-backed rotating token like
`RefreshToken`/`RegistrationVerificationToken` use. Those exist for *user-facing* secrets that need
expiry/rotation/reuse-detection; this is a single internal caller (the future pipeline), the same
trust level as `OTP_HASH_SECRET` or any other internal-service credential in this app.

**Webhook auth is a custom header (`X-Pipeline-Webhook-Secret`), not `Authorization: Bearer`.**
`SecurityConfig`'s OAuth2-resource-server JWT filter inspects any `Authorization: Bearer` header and
can reject it as an invalid JWT before `permitAll()` is even evaluated — this exact class of mismatch
(OpenAPI says public, Spring Security actually rejects it) is what broke CORS once already in this
repo's history (`poc-catalog.md`, `jwt-authentication.md`). A distinct header sidesteps that filter
entirely; the matcher (`.requestMatchers(HttpMethod.POST, "/pocs/deployments/*/status").permitAll()`)
follows the exact same unprefixed-path convention as the existing `GET /pocs` matcher.

**`202 Accepted`, not `201 Created`, on the two trigger endpoints.** Deliberately distinct from this
API's create-returns-201 idiom, to signal "kicked off, not finished" — the caller should poll
`GET /pocs/deployments/{deploymentId}` for the actual outcome.

**`GET /pocs/deployments/{deploymentId}` is a flat path, not nested under `/pocs/{id}`.**
`deploymentId` is a globally-unique UUID; this is also the shape the pipeline's own status callback
addresses, so the read path matches the write path.

**`DeploymentTrigger` is a plain interface with a logging-only stub (`LoggingDeploymentTrigger`),
deliberately inert rather than auto-advancing status on a timer.** The intended test loop for this
feature, with no real pipeline yet, is manual: click Deploy, watch `PENDING` appear, then curl the
status callback through `BUILDING` → `DEPLOYING` → `SUCCEEDED` yourself. A timer that auto-advanced
status would race a manual curl call, making that loop non-deterministic. When a real (likely
networked) trigger implementation is wired in later, it replaces this class or sits behind a
profile — nothing above the interface (`PocDeploymentService`, the controller, the frontend) needs to
change. The persist calls happen before the trigger call is invoked (not after, inside the same
`@Transactional` method) — a deliberate ordering, not a strict isolation guarantee, since the
callback racing an uncommitted row is a real (if currently theoretical, given the synchronous no-op
stub) concern for whatever real trigger eventually replaces it.

**`GET /pocs`'s `latestDeploymentStatus` is computed with one batched query for the whole list**
(`PocDeploymentRepository.findLatestPerPoc`, a native `DISTINCT ON (poc_id) ... ORDER BY poc_id,
started_at DESC`), not per-row — avoids an N+1 across the dashboard's POC grid.

## Data Model

**`poc_versions`** (immutable once `container_image` is set):
| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| poc_id | BIGINT NOT NULL REFERENCES pocs(id) ON DELETE CASCADE | |
| major, minor, patch | INTEGER NOT NULL | `CHECK (patch BETWEEN 1 AND 20)` |
| version_label | VARCHAR(20) NOT NULL | e.g. `"1.2.4"` |
| container_image | VARCHAR(500) | nullable until a successful `BUILD_AND_DEPLOY` |
| created_at | TIMESTAMPTZ NOT NULL DEFAULT now() | |

`UNIQUE (poc_id, major, minor, patch)`.

**`poc_deployments`** (one row per deploy attempt):
| column | type | notes |
|---|---|---|
| id | UUID PK | app-generated, same style as `RefreshToken`/`RegistrationVerificationToken` — the correlation id the pipeline echoes back |
| poc_id | BIGINT NOT NULL REFERENCES pocs(id) ON DELETE CASCADE | |
| poc_version_id | BIGINT NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE | for `REDEPLOY`, the *existing* version row |
| kind | VARCHAR(20) NOT NULL | `BUILD_AND_DEPLOY` \| `REDEPLOY` |
| status | VARCHAR(20) NOT NULL DEFAULT 'PENDING' | `PENDING` \| `BUILDING` \| `DEPLOYING` \| `SUCCEEDED` \| `FAILED` |
| logs_url | VARCHAR(500) | nullable — a link out, no log text is stored |
| error_message | VARCHAR(2000) | nullable, set on FAILED |
| initiated_by | VARCHAR(36) REFERENCES users(id) | nullable, admin who triggered it |
| started_at / completed_at / updated_at | TIMESTAMPTZ | `completed_at` set on reaching a terminal status |

**`pocs`**: `version`/`container_image` columns removed (migration `V13`); `active_version_id BIGINT
REFERENCES poc_versions(id)` added (nullable — null means never successfully deployed).

## API Surface

All new endpoints tagged `Deployment` (generates `DeploymentApi`, implemented by
`PocDeploymentController`), admin-only except the webhook:

- **`POST /pocs/{id}/deploy`** — admin. Allocates the next version, records a `PENDING` deployment,
  triggers the pipeline. `400` if `githubUrl` is blank. `202` `PocDeploymentResponse`.
- **`GET /pocs/{id}/versions`** — admin. All versions, newest first, each flagged `isActive`.
- **`POST /pocs/{id}/versions/{versionId}/redeploy`** — admin. Records a `REDEPLOY` deployment
  against the existing version, triggers a redeploy of its image. `409` if that version never
  successfully built.
- **`GET /pocs/{id}/deployments`** — admin. Deployment history, newest first.
- **`GET /pocs/deployments/{deploymentId}`** — admin. Single deployment, for polling live status.
- **`POST /pocs/deployments/{deploymentId}/status`** — the webhook. `X-Pipeline-Webhook-Secret`
  header, not a bearer token. Body `{status, containerImage?, logsUrl?, errorMessage?}`.
  `containerImage` required when reporting `SUCCEEDED` for a `BUILD_AND_DEPLOY` deployment. `409` if
  the deployment already reached a terminal status.

`GET /pocs`/`GET /pocs/{id}` (existing endpoints) gain `activeVersion` (string label, public — same
gating `version` already had) and `latestDeploymentStatus` (nullable, feeds the dashboard's
"Deploying…"/"Deploy failed" pill). `PocResponse` (authenticated) additionally gains
`activeVersionId`. `CreatePocRequest`/`UpdatePocRequest` drop `version`/`containerImage` — fully
automatic now, no manual entry.

## Security Considerations

- All read/write endpoints except the webhook require `ADMIN` (`@PreAuthorize("hasRole('ADMIN')")`).
- The webhook is unauthenticated in the normal JWT sense — it's gated by a shared secret header
  instead, since the caller is the pipeline, not a logged-in user. `MessageDigest.isEqual` for a
  constant-time comparison.
- `self-service-api` never holds GCP credentials or calls GCP directly — the pipeline (whatever
  implements `DeploymentTrigger` for real, later) owns that entirely; this app only tracks state
  reported back to it.

## Open Questions / Future Work

- **No rate limiting** on `/pocs/{id}/deploy` or the webhook — an admin (or, if the secret leaked, an
  attacker) could trigger unbounded deployments. Not addressed here.
- **No retry-under-the-same-number** for a failed build — each "Deploy new version" click always
  allocates the next number, whether or not the previous attempt succeeded.
- **No audit trail beyond `initiated_by`/`started_at`** — consistent with the rest of this app not
  having one yet (`admin-customers.md` flags the same gap for revoke/extend).
- **No hard-delete/purge** of `poc_versions`/`poc_deployments` rows.
- **Major version never bumps automatically, and there's no manual control for it either** — every
  version is `1.x.x` for now. Revisit if a POC's history needs a real "breaking change" marker.
- **Trigger ordering is not transactionally guaranteed** — the persist calls happen before the
  trigger call within the same `@Transactional` method, not strictly after commit. Harmless with
  today's synchronous no-op stub; worth revisiting (e.g. firing the trigger from an
  after-commit hook) once a real, networked `DeploymentTrigger` implementation exists.

## Changelog

- 2026-09-01 — Implemented: `V13__create_poc_versions_and_deployments.sql`,
  `PocVersion`/`PocDeployment` entities and repositories, `PocDeploymentService`
  (version allocation, redeploy validation, status-callback handling, batched
  active-version/latest-status lookups for `GET /pocs`), `DeploymentTrigger` +
  `LoggingDeploymentTrigger` stub, `poc-deployment.yaml` schema, `Deployment` tag →
  `PocDeploymentController`, `SecurityConfig` webhook matcher, `DeploymentWebhookProperties`.
  `pocs.version`/`container_image` removed, `active_version_id` added. Unit-tested
  (`PocDeploymentServiceTest`, 14 tests: version allocation incl. the patch→minor rollover, status
  transitions, redeploy validation). Full `mvn test` (97/97) passes.
- 2026-09-01 — Initial draft, written before implementation.

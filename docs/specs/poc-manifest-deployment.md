# POC Manifest-Driven Multi-Container Deployment

## Status

Implemented

## Overview / Purpose

Until this feature, the deploy pipeline (`docs/specs/poc-deployment-pipeline.md`) could only ever
build one Dockerfile at the repo root and deploy it as a single-container Cloud Run service. A POC
that needs a sidecar — a backend split from a frontend, a local vector DB, a worker process — had
nowhere to declare that; every POC repo was assumed to be exactly one container named implicitly
`app`.

This feature adds `poc.yaml`, an optional manifest at a POC repo's root that declares one or more
containers (`ingress` plus any number of `sidecar`s), each with its own Dockerfile/build context,
and deploys all of them as a single Cloud Run service using Cloud Run's native multi-container
support (`gcloud run deploy --container=...` repeated per container). A repo with no `poc.yaml`
keeps working exactly as before — `ManifestService` synthesizes the same single-container manifest
(`app`, root `Dockerfile`) that today's behavior already assumes, so this is purely additive.

## Requirements

- A POC repo may declare a `poc.yaml` with one or more containers; a repo with none deploys exactly
  as it did before this feature existed.
- Exactly one container is the ingress (receives Cloud Run's `$PORT`, gets the service's public
  entry point); any others are sidecars, reachable only from the ingress container.
- Each container may have its own Dockerfile and build context within the same repo — no separate
  clone per container.
- A bad manifest must fail before anything is cloned or built, with every violation reported at
  once, not one per retry.
- A sidecar's built image and port must be recoverable later for redeploy/rollback, without needing
  a fresh GitHub read of `poc.yaml` (which may have changed since that version was built).
- Total resource limits (CPU/memory) apply to the service as poc.yaml's author understands it — one
  block — not per container.

## Architecture Decisions

**One Cloud Run service, not one per container.** Cloud Run's own multi-container support
(`--container=<name>` repeated) deploys an ingress plus its sidecars as a single service with one
URL, matching how `poc.yaml` describes them
("the whole service") and avoiding a second layer of inter-service networking/IAM this platform
doesn't otherwise have. See `CloudRunDeployCommandBuilder`.

**`ContainerRole.INGRESS`/`SIDECAR`, exactly one ingress per manifest, enforced twice.**
`ManifestValidator` rejects a manifest with zero or multiple ingress containers before any build
starts; a database constraint (`uq_pvc_one_ingress_per_version`, partial unique index on
`role = 'INGRESS'`) enforces the same invariant on what actually got persisted, regardless of what
wrote the row.

**Which container declares a port, and why it changed.** Cloud Run identifies a multi-container
service's ingress container as *the one with the exposed port*, and gives it no default: "for a
service containing sidecars, there is no default port for the ingress container. You must
explicitly configure the container port for the ingress container and only one container can have
the port exposed."

This spec originally said the opposite — that the ingress must *not* declare a port because it
binds `$PORT` — and the implementation matched, emitting `--port` for exactly the containers that
declared one, i.e. only sidecars. The result was that Cloud Run treated the *sidecar* as the
ingress container: external traffic reached the wrong process, and the real ingress never received
a request or a `$PORT`. The current rules:

- An ingress container **may** declare a port, and it is emitted as that container's `--port`. It is
  not required: the ingress port is platform-owned (`poc-runtime.ingress-port`, default 8080) and
  supplied when the manifest names none, so a manifest that declares no ingress port is complete
  and one that declares its own still wins. Requiring it would reject every multi-container repo
  already written against `poc-platform-sdk`'s published schema, for a value the platform can
  always supply. A lone ingress (no sidecars) deploys through the plain `--image=` form, which
  Cloud Run defaults to 8080 for on its own.
- A sidecar **must** declare a port — but it is never passed to gcloud as that container's `--port`.
  It exists so the platform can inject `SVC_<NAME>_URL` for the other containers, and `PORT` for the
  sidecar itself.
- Every sidecar is explicitly given `--port=default` (gcloud's documented "unset" value). A deploy
  is a merge into the existing service, not a replacement, so a service first deployed under the
  old, inverted behaviour would otherwise keep its sidecar's port forever and Cloud Run would
  reject every subsequent revision with "should contain exactly one container with an exposed port"
  once the ingress correctly got one too. Clearing it every time makes the deploy self-healing.
- Rollback re-parses a stored manifest, which never passes through `ManifestValidator` — a version
  built before any of this has no ingress port at all. The same platform default covers it, rather
  than emitting `--port=null`, since a stored manifest is immutable history and nothing an admin
  could edit would fix it.

This is deliberately *not* a breaking change to the manifest contract. An earlier revision of this
spec did require an ingress port once sidecars existed, which would have failed validation on every
multi-container `poc.yaml` already written against `poc-platform-sdk`'s schema; making the platform
own the value instead keeps those repos deploying unchanged while still giving Cloud Run the
explicit port it needs.

**`Resources` (cpu/memory) applies to the ingress container only.** Cloud Run bills the *sum* of
every container's own resource limits, and `poc.yaml` has one `resources:` block for what it calls
"the whole service." Sidecars get Cloud Run's own built-in default instead of a value invented here,
which avoids landing on a fractional CPU value Cloud Run doesn't accept.

**Platform-owned env vars the manifest can't override.** `manifest.reserved-env-names` (`PORT`,
`POC_SLUG`, `PLATFORM_API_URL`) and the `SAILS_`/`SVC_` prefixes are rejected outright by
`ManifestValidator` if a container's `env:` sets them, because the platform injects them itself in
`CloudRunDeployCommandBuilder.platformEnv`:

- `PLATFORM_API_URL` and `POC_SLUG`, into every container. The first is how a POC's backend reaches
  this API's JWKS to verify a launch token; the second is the slug it builds its expected `poc:<slug>`
  audience from — which must come from its own configuration, never from the token being validated.
- `SVC_<NAME>_URL` (e.g. `SVC_WORKER_URL=http://localhost:9000`) per sidecar, into every *other*
  container, so an ingress reaches a sidecar by name instead of hardcoding a port per-repo.
- `PORT`, into each sidecar, set to that sidecar's own declared port. Cloud Run injects `PORT` into
  the ingress container only, and every sidecar's port is deliberately cleared (above) — so this is
  the sole way a sidecar can learn the port the platform is simultaneously advertising for it, and
  it cannot supply the value itself because the name is reserved. Both are written in one place so
  they cannot drift; `CloudRunDeployCommandBuilderTest` pins that they agree.

- `PORTAL_ORIGIN`, into every container: the one origin allowed to frame this POC. It is both the
  `postMessage` targetOrigin a POC replies to and the value it puts in its own
  `Content-Security-Policy: frame-ancestors`, so the JavaScript origin check and the
  browser-enforced embedding restriction cannot disagree. Platform-supplied precisely so a POC never
  derives it from `document.referrer` or `location.ancestorOrigins`, both of which an embedder
  controls. Configured as `poc-runtime.portal-origin`, defaulting to `app.frontend.url`.

A container's `health:` path becomes a per-container `--startup-probe=httpGet.path=…,httpGet.port=…`
against the port that container actually listens on, and the ingress gets `--depends-on=` naming
exactly those sidecars that declared one — Cloud Run rejects a dependency on a container with no
startup probe, so tying it to `health:` keeps a probe-less manifest deployable rather than turning
an optional key into a required one. Without the ordering, the ingress can proxy to a sidecar that
isn't listening yet, which is a 502 on every cold start.

An earlier revision of this document described `SVC_<NAME>_URL` injection as already shipped when no
code did it, and listed a `DATABASE_URL` reservation that has never existed. Both are corrected
above.

**`poc_versions.manifest_yaml` stores the exact manifest a version was built with; redeploy never
re-reads `poc.yaml` from GitHub.** A repo's `poc.yaml` can change between a version's original build
and a later rollback to it — redeploy must reconstruct exactly what was deployed *then*.
`ManifestService.resolveStored` parses the stored copy (or synthesizes the same single-container
default, for every pre-manifest version and every version whose repo simply had no `poc.yaml`) —
no GitHub call, no clone, on the redeploy path at all.

**`poc_version_containers` is purely additive — existing versions simply have no rows.**
`poc_versions.container_image`/`commit_sha` keep meaning exactly what they mean today (the ingress
container's image; the repo's commit); the new table adds one row per container a version actually
built, populated going forward only. `PocDeploymentService.resolveImagesByContainer` falls back to
the single `container_image` under the synthesized default's ingress name (`"app"`) when a version
has no rows here — what makes an old, pre-manifest version redeploy correctly through the new,
manifest-aware pipeline with zero data migration.

**Manifest parsing/validation is shared, build/deploy execution isn't (by design).**
`ManifestParser`/`ManifestValidator`/`ManifestService` are single implementations used by both
`LocalPipelineExecutor` (real subprocesses) and `CloudBuildPipelineExecutor`/`BuildService` (Cloud
Build API steps) — a manifest means the same thing regardless of executor. The *build step
construction* is intentionally not shared beyond that: a local `docker build` argv and a Cloud
Build step's `args` are different mechanisms, and forcing them through one abstraction would cost
more than the ~10 lines of overlap it would save. `CloudRunDeployCommandBuilder`, in contrast, *is*
shared between both executors' `deploy()` — the `--container`/`--port`/`--set-env-vars` flag logic
is one non-trivial piece of knowledge that must not drift between the two paths. (`--min-instances`
/`--max-instances` are the exception: service-level, so each executor emits them before its own
first `--container=`, at the cost of a duplicated `addScalingArgs`.)

**Cross-repository containers are not modeled at all.** Every container builds from the primary
repo. A `repo:` key in a manifest is silently ignored, like any other unrecognised key —
`ManifestContainer` has no such field and `ManifestValidator` has no rule for it, so an author who
writes one gets a build from the wrong source with no explanation. (An earlier revision of this
document described the field as existing and being rejected with a clear message; it never has.
Adding it purely so it can be rejected is still the right call, and is listed under Future Work.)

**`platform.database`/`platform.files` parse but do nothing yet.** A manifest declaring
`platform: {database: {enabled: true}}` validates cleanly and is stored, but nothing in the pipeline
acts on it — deferred to when the underlying platform capability (a provisioned database, wired
file storage per POC) actually exists. Parsing it now means a repo author's manifest doesn't need
to change shape when that lands.

## Data Model

**`poc_versions`** (migration `V19__poc_version_containers.sql`) gains:
| column | type | notes |
|---|---|---|
| manifest_yaml | TEXT, nullable | The manifest exactly as built, or null for a repo with no `poc.yaml`. Redeploy parses this, never a fresh GitHub read. |

**`poc_version_containers`** (new table, `V19__poc_version_containers.sql`, entity
`poc/entity/PocVersionContainer.java`) — one row per container a version actually built:
| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| poc_version_id | BIGINT NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE | |
| name | VARCHAR(40) NOT NULL | |
| role | VARCHAR(16) NOT NULL, `CHECK (role IN ('INGRESS','SIDECAR'))` | |
| container_image | TEXT, nullable | |
| port | INTEGER, nullable | Set for a sidecar; null for ingress. |

`UNIQUE (poc_version_id, name)`; a partial unique index `uq_pvc_one_ingress_per_version` on
`poc_version_id WHERE role = 'INGRESS'` enforces exactly-one-ingress at the database level, not just
in `ManifestValidator`.

> **Note:** this migration was originally checked in as `V18__poc_version_containers.sql`. It was
> renumbered to `V19` on 2026-09-06 after merging with `develop` (which had independently taken
> `V18` for `V18__create_user_files_table.sql`, from the file-management feature) revealed both
> shared the same version number — Flyway requires unique versions, and this would have failed
> every startup with "Found more than one migration with version 18."

## API Surface

`GET /pocs/{id}/versions` (`PocDeploymentController.getPocVersions`, unchanged path, extended
response):

- `PocVersionResponse` gains `containers: PocVersionContainerResponse[]` — every container that
  version's manifest declared, as actually built. Empty (not omitted) for a version built before
  this feature existed. `containerImage` (singular, the ingress image) is kept alongside it
  unchanged, since every version has that field regardless of age.
- New schema `PocVersionContainerResponse { name, role: INGRESS|SIDECAR, containerImage?, port? }`.

No other endpoint changed shape. `POST /pocs/{id}/versions/{versionId}/redeploy` and the internal
`RedeployRequest`/`BuildAndDeployRequest` already carried `Map<String,String> imagesByContainer`
keyed by container name (not a single image) from this feature's first implementation — see
Changelog.

## Security Considerations

- A manifest is fully validated (`ManifestValidator`) before anything is cloned or built — a bad
  `poc.yaml` fails fast with every violation listed, not as a confusing failure partway through a
  build attempt.
- `ManifestValidator` rejects any container `env:` entry that collides with a reserved name
  (`PORT`, `POC_SLUG`, `PLATFORM_API_URL`, `PORTAL_ORIGIN`) or the `SAILS_`/`SVC_` prefixes, so a POC repo cannot
  clobber a value the platform injects (e.g. another sidecar's `SVC_*_URL`) via its own manifest.
- `Cloud Run's own per-service container limit (8)` is enforced in `ManifestValidator` before a
  build attempt, not discovered as a `gcloud` error partway through a deploy.
- Cross-repository containers are explicitly rejected rather than silently ignored — a manifest
  author would get a clear reason a `repo:` field didn't do what they expected, rather than a build
  that quietly used the wrong source — not implemented, see Future Work.
- No new secret-handling surface: the GitHub token, Cloud Build service-account, and Cloud Run
  `--service-account=` behavior are unchanged by this feature — every container in a manifest
  deploys under the same `poc-runtime` identity and the same `--allow-unauthenticated`/invoker-grant
  behavior `poc-deployment-pipeline.md` already documents.

## Open Questions / Future Work

- **No example `poc.yaml` exists anywhere in this repo** for a POC author to copy from — only test
  fixtures (`ManifestParserTest`, `ManifestValidatorTest`). A POC team building a multi-container
  repo today has to reverse-engineer the schema from `ManifestContainer`/`ManifestParser` or this
  spec. Worth a short reference doc or a `poc.yaml.example` once a real multi-container POC exists
  to validate it against.
- **Cross-repository containers** are unmodelled — every container still builds from the primary
  repo, and a `repo:` key is silently ignored rather than rejected. Adding the field purely so
  `ManifestValidator` can reject it with a specific message is the cheap first step. Supporting a second repo means `ManifestService`
  resolving more than one `GitHubRepoRef`/commit and each executor cloning more than once.
- **`platform.database`/`platform.files`** parse and validate but drive no behavior yet — deferred
  to whichever phase actually provisions a per-POC database or wires file storage per POC.
- **`docs/specs/poc-deployment-pipeline.md` is now significantly stale** against the current
  codebase (it describes a `DeploymentOrchestrator`/`DeploymentStatusPoller` polling design that
  `PipelineRunner`'s synchronous, in-process status reporting has since replaced, and calls out
  "single hardcoded image component" as an open question that this feature resolves). It should be
  revised or marked `Superseded` in a follow-up pass rather than left to contradict this document.
- **No end-to-end test exercises a real two-container deploy** (both `docker build` steps, both
  Cloud Run `--container=` flags, the resulting service actually reachable). Coverage today is at
  the unit level — `ManifestParserTest`, `ManifestValidatorTest`, `CloudRunDeployCommandBuilderTest`,
  and `PocDeploymentServiceTest`'s container-persistence tests — which verify each stage in
  isolation but not a real Cloud Run service coming up with a working sidecar.

## Changelog

- 2026-09-07 — Corrected two things this document asserted that were never true, both found when
  the first real multi-container POC deployed successfully and could not be opened. The
  ingress/sidecar port rule was stated backwards (see "Which container declares a port"): Cloud Run
  identifies the ingress container as the one with the exposed port, so emitting `--port` for the
  containers that declared one made the *sidecar* the ingress and sent every external request to
  the wrong process. And `SVC_<NAME>_URL` injection was described as shipped when no code performed
  it; it exists now, alongside `PLATFORM_API_URL`, `POC_SLUG`, and a sidecar's own `PORT`. The
  `DATABASE_URL` reservation listed here has never existed and has been dropped rather than added.
  Two further claims were corrected in the same pass: this document said `--depends-on=` and
  per-container `--startup-probe=` flags were emitted, and that `ManifestContainer.repo` existed so
  a cross-repository container could be rejected with a clear message. Neither was true at the time
  and both were restated as gaps.

- 2026-09-07 — Closed those gaps against `poc-runtime-contract.md`, which specifies what a deployed
  POC must look like from a browser and from inside the instance. `health:` now becomes a
  per-container `--startup-probe=`, the ingress gets `--depends-on=` for every probed sidecar, a
  container's `repo:` is parsed and rejected by name instead of vanishing, and any other
  unrecognised key is logged as `unsupported manifest keys, ignored: …` rather than dropped in
  silence. `PORTAL_ORIGIN` is injected alongside `PLATFORM_API_URL`/`POC_SLUG` and reserved with
  them. The three runtime values a POC may rely on moved to their own `poc-runtime.*` namespace
  (`ingress-port`, `platform-api-url`, `portal-origin`) — `PLATFORM_API_URL` is still honoured as
  the older env var name, so an environment configured before the move keeps working. The ingress
  port requirement was relaxed from *must* to *may* in the same pass, making the platform own the
  value rather than breaking every manifest already written against the published schema. Service-
  level flags now come from `CloudRunDeployCommandBuilder.buildServiceArgs`, so both executors
  build one service description from one place instead of each keeping a private copy of the
  scaling flags — which had already drifted.

- 2026-09-06 — Reviewed the feature end-to-end (manifest parsing → build → deploy → persistence) to
  confirm it's complete for deploying a multi-container POC from one repo via `poc.yaml`. Found and
  fixed a real startup-blocking bug: `V18__poc_version_containers.sql` collided with a same-numbered
  migration merged in from `develop` (`V18__create_user_files_table.sql`) — renumbered to `V19`.
  Found and closed a read-side gap: `poc_version_containers` was written by `PocDeploymentService`
  but never read back by any endpoint — `GET /pocs/{id}/versions` now returns each version's full
  `containers` list (`PocVersionContainerResponse`), batch-loaded via a new
  `PocDeploymentService.containersByVersionId`. Corrected stale documentation on `DeploymentTrigger`
  and `LoggingDeploymentTrigger` (claimed the pipeline was unbuilt and that self-service-api never
  calls GCP directly — both no longer true; the logging stub's example curl command also pointed at
  a nonexistent `/api/v1` path prefix). Full `mvn test` (244/244) passes after each change.
- Undated (prior to this review) — Initial implementation: `ManifestContainer`/`ContainerRole`/
  `Platform`/`Resources`/`Scaling`/`PocManifest` records, `ManifestParser`, `ManifestValidator`,
  `ManifestService` (including the single-container default synthesis and stored-manifest
  redeploy resolution), `CloudRunDeployCommandBuilder` (shared multi-container `gcloud run deploy`
  arg construction), both `LocalPipelineExecutor` and `BuildService` updated to build/push one image
  per manifest container, `poc_version_containers` table and entity, and
  `PocDeploymentService`/`PipelineRunner` wiring to persist and redeploy every container's image.
  Unit-tested (`ManifestParserTest`, `ManifestValidatorTest`, `CloudRunDeployCommandBuilderTest`,
  container-persistence cases in `PocDeploymentServiceTest`). This spec is being written
  retroactively (see the 2026-09-06 entry above) — this repo's own convention
  (`docs/specs/README.md`) calls for a spec *before* implementation, which didn't happen here.

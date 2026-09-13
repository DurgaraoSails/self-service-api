# POC Container Environment & Secrets

## Status

In Progress

## Overview / Purpose

Today a deployed container's environment comes from exactly two places: literal `env:` entries in
the repo's `poc.yaml`, and the reserved values the platform injects itself (`POC_SLUG`,
`PLATFORM_API_URL`, `PORTAL_ORIGIN`, `SVC_<NAME>_URL`, a sidecar's `PORT` — see
`poc-manifest-deployment.md`). Both are fixed at the moment the manifest is written.

That leaves no way to give a POC a value that is *secret* or that *differs per deployment*. A POC
needing an OpenAI key, a database password, or a per-environment endpoint has two options today:
commit the value to a git-tracked `poc.yaml`, or not use the platform. Neither is acceptable, and
the first is actively dangerous — `poc.yaml` is read from a GitHub repo that POC authors control
and reviewers rarely audit.

This feature adds the two missing layers: **admin-supplied overrides** (per POC, per container, set
in the portal) and **secrets** (supplied the same way, but stored in Google Secret Manager and
resolved by Cloud Run at container start, never by this platform). A manifest gains a `requires:`
block so an author can declare *what their code needs* without knowing — or being able to see — the
value. A repo with no `requires:` and no admin overrides deploys exactly as it does today.

## Requirements

- A secret's value must never be stored by this platform, never appear in `poc.yaml`, never appear
  in Cloud Build logs or on the Build resource, and never appear in the Cloud Run service's env
  spec.
- An admin with no GCP console access and no `gcloud` must be able to supply one.
- A manifest author declares what a container needs; they do not supply, name, or see the value.
- A deploy whose requirements are unsatisfied must fail *before* anything is cloned or built, naming
  the missing keys.
- A secret is write-only: once submitted it can be replaced, but never read back — not by the
  portal, not by an API call, not by an admin.
- Deleting a POC must leave no orphaned Secret Manager secrets behind.
- Every manifest that deploys today must keep deploying unchanged.

## Architecture Decisions

**Four layers, resolved in a fixed precedence order.** A container's final environment is the merge
of, highest priority first:

| Layer | Owner | Where it lives | Mechanism |
|---|---|---|---|
| Platform-injected | the platform | `PocRuntimeProperties`, computed per deploy | `--set-env-vars` |
| Admin secret | an admin, by value | Secret Manager; only a binding row here | `--set-secrets` |
| Admin plain override | an admin, by value | `poc_container_env.value` | `--set-env-vars` |
| Manifest default | the POC author | `env:` in `poc.yaml` | `--set-env-vars` |

Platform values win unconditionally — they already cannot be set from a manifest
(`manifest.reserved-env-names`), and the same rejection must apply to an admin override, or an
admin could shadow `PLATFORM_API_URL` and silently break token verification for that POC. Secrets
outrank plain overrides only so that promoting a value from plain to secret is a one-way,
unambiguous change rather than leaving two live entries for one key; the `UNIQUE` constraint below
means both cannot exist for the same key anyway, so this ordering is belt-and-braces.

**The manifest declares the need, not the value.** A new per-container `requires:` block:

```yaml
containers:
  - name: backend
    role: sidecar
    port: 8081
    env:
      LOG_LEVEL: info                  # default; an admin override wins
    requires:
      - name: DATABASE_URL             # admin must supply a plain value
      - name: OPENAI_API_KEY
        secret: true                   # admin must supply a secret; never stored plaintext
```

This splits ownership along the line where the knowledge actually sits: the *author* knows what
their code reads (a fact about the code, versioned with it), the *admin* knows the value (a fact
about the environment, which changes without a commit). The `secret: true` marker is the author's,
not the admin's, because sensitivity is a property of what the variable *is* — an admin filling in a
form should not have to decide whether an API key deserves Secret Manager.

*Rejected: putting Secret Manager ids in `poc.yaml`* (e.g. `secrets: {OPENAI_API_KEY: openai-key}`).
It forces a POC author to know Secret Manager exists, to invent a project-globally-unique id, and to
commit a value that couples a portable repo to one specific GCP project. It also gives the author a
way to point a binding at *another POC's* secret, which is a cross-tenant read the platform would
then have to police. Deriving the id from `(slug, container, key)` removes all three problems.

**`--set-secrets`, never fetch-and-pass.** The deploy emits, scoped to each container:

```
--container=backend --set-secrets=^;^OPENAI_API_KEY=poc-testbed-one-backend-openai-api-key-dev:latest
```

`--set-secrets` is confirmed container-scoped: `gcloud run deploy --help` lists it under **Container
Flags** — *"these flags may only be specified after a `--container` flag"* — alongside `--image`,
`--port` and `--set-env-vars`.

*Rejected: resolving the secret in `self-service-api` and passing it via `--set-env-vars`.* The
deploy step's `args` are stored permanently on the Cloud Build resource — this is the exact exposure
`BuildService.cloneStep` already documents and avoids for the GitHub token, and it would be strictly
worse here because a POC secret has no equivalent of the token's "only for a repo you don't mind
exposing" escape hatch. It would also put the plaintext through Cloud Build's logs.

`--set-secrets` shares `--set-env-vars`' replace-not-merge semantics (*"All existing secrets will be
removed first"*), so a removed binding actually disappears on the next deploy. This is deliberately
unlike `--port`, whose merge semantics caused the 2026-09-07 "exactly one container with an exposed
port" incident; no `--clear-secrets` equivalent is needed.

**Write-only, enforced by the schema rather than by discipline.** The platform stores a *binding*
(`poc_id`, `container_name`, `env_key`, `secret_id`) and never a secret value. A `CHECK` constraint
makes `value IS NULL` structurally required whenever `kind = 'SECRET'`, so no future endpoint,
mapper, or well-meaning refactor can start persisting one — the same reasoning as
`uq_pvc_one_ingress_per_version` enforcing in the database what `ManifestValidator` enforces in code.

No endpoint returns a secret value. The portal shows `OPENAI_API_KEY — set · updated 3 days ago by
alice@…` with a **Replace** action.

*Rejected: a masked field with a reveal toggle.* Reveal requires a read path, a read path requires
the value to be retrievable, and retrievable is precisely what "secret" must not be. GitHub Actions,
Vercel and Netlify all landed on write-only for this reason.

**The portal collects the value; admins need no GCP access.** The admin types it into the portal;
`self-service-api` writes it straight through to Secret Manager and discards it.

*Rejected: the admin creates the secret in GCP themselves and the portal records only its name.*
This platform exists so that POC teams do not need GCP IAM — a model that requires every admin to
hold `secretmanager.admin` and know `gcloud` defeats that, and in practice admins would simply
bypass the portal. The accepted cost is that the value transits this API's memory for the duration
of one request; the mitigations are in Security Considerations, and the value is never written to
this platform's disk at any point.

**Secret naming: `poc-<slug>-<container>-<key>`, lowercased and hyphenated**, then passed through
the existing `-<environment>` suffix convention, giving e.g.
`poc-testbed-one-backend-openai-api-key-dev`. Deriving it rather than accepting one means it is
collision-proof across POCs by construction, prefix-queryable (`poc-<slug>-`) for deletion, and
impossible to point at another POC's secret. The `-<environment>` suffix matches
`GcpProperties.secretVersionName` so a `dev` and a `prod` deployment of the same slug do not collide
in a shared project; a new `GcpProperties.pocSecretId(slug, container, key)` should own the
construction so the convention lives in one place.

**Version `:latest`, not a pinned version.** Rotating a secret takes effect on the POC's next deploy
with no binding change. The cost is that a rollback to version 1.0.4 runs with *today's* secret
rather than the one that version originally ran with — the same reproducibility tradeoff admin
overrides introduce generally, and acceptable for a demo platform where the alternative is that
rotating a leaked key requires editing every POC that used it. See Future Work.

**Every POC gets its own runtime service account. This must land before the first secret exists.**
Today every deployed POC runs as one shared identity, named at `BuildService.deployStep` as
`gcp.serviceAccountEmail("poc-runtime")`. Per-secret grants to a shared identity do not isolate
anything: they accumulate into one pool that every hosted POC's container can draw from. Secret ids
are derived from the slug and are therefore guessable, a Cloud Run container can reach the metadata
server and mint a token for the identity it runs as, and Secret Manager is a public API. Any POC
could read any other POC's secrets by asking for them.

That makes the shared account a blocker for this feature rather than a detail of it. `pocs` gains a
`runtime_service_account` column (migration `V16`), allocated once per POC and passed to
`--service-account=` at deploy. Two properties matter:

- **Allocate, don't derive at use.** A GCP account id is 6–30 characters and `pocs.slug` is
  unbounded `TEXT`, so `poc-<slug>-<environment>` overflows for any slug past roughly fifteen
  characters. The id is derived once — truncated, with a short deterministic digest appended for
  uniqueness — and then *stored*, so nothing depends on that derivation staying stable if the
  truncation rule is ever changed. A null column means the POC predates this and keeps using
  `poc-runtime-<environment>`, which stays defined in Terraform as the fallback.
- **The builder must be able to act as it.** Creating the account is not enough: Cloud Run refuses
  `--service-account=` unless the deploying identity holds `roles/iam.serviceAccountUser` on the
  target, even with `run.admin`. `iam.tf`'s existing `builder_can_act_as_poc_runtime` binding exists
  for exactly that reason and says so in a comment. Terraform cannot grant it for accounts that do
  not exist yet, so `self-service-api` sets it on the new account at creation — the same shape as
  the dynamic per-POC `run.invoker` grant `grantApiInvokerStep` already makes, and gated by a
  property the same way, so a developer running under `roles/editor` is not blocked.

`ensureRuntimeServiceAccount(poc)` is idempotent get-or-create, called from both the bind path and
the deploy path, because a secret cannot be granted to an account that does not exist and a deploy
cannot name one either.

Deleting a POC must delete its account along with its secrets. Slugs are `UNIQUE` and soft deletion
keeps them reserved, so a deleted slug can never be reused into a stale account.

*Operational ceiling worth knowing before committing:* a GCP project allows 100 service accounts by
default. That is a cap on concurrently hosted POCs and is raised by a quota request, not by code.

**IAM is granted when the binding is created, not when the deploy runs.** The POC's own runtime
service account needs `roles/secretmanager.secretAccessor` on each secret it consumes. Granting at
bind time means a permission problem surfaces immediately, in the portal, attached to the action
that caused it. Granting at deploy time means the deploy *succeeds* and the container then
crash-loops with a Secret Manager permission error that looks nothing like a secrets problem — the
failure mode this platform has already been bitten by twice.

Per-secret, per-consumer bindings, not a project-wide accessor grant — following the precedent
`poc-deployment-pipeline.md` sets for `self-service-builder`'s scoped access to `github-token`.

**A secret is never readable from an `env:` placeholder.** `poc-manifest-deployment.md` adds
`${...}` references that resolve inside `env:` values; there is deliberately no `${secrets.X}` among
them and there must never be. An `env:` value is emitted through `--set-env-vars`, whose arguments
are stored permanently on the Cloud Build resource — the exact exposure `--set-secrets` exists to
avoid. A secret reaches a container as its own variable or not at all, so composing one into a
larger string (a password inside a connection URL, say) is not supported; the whole URL is the
secret instead. `ManifestValidator` rejects the reference rather than leaving it to resolve to
nothing.

The grant needs `secretmanager.secrets.setIamPolicy`, which is the same permission class that
already forces `pipeline.grant-api-invoker=false` for local runs under `roles/editor`. It must
therefore degrade the same way: **if the grant fails, store the binding anyway, deploy anyway, and
log a warning naming the exact `gcloud secrets add-iam-policy-binding` command to run by hand.**
Refusing the binding would make the feature unusable in exactly the environment most people develop
in.

**Secret Manager over REST, not the client library.** `poc-deployment-pipeline.md` states the rule —
zero GCP client libraries for control-plane calls, because the SDK pulls gRPC and a large dependency
surface for what are a handful of JSON endpoints. `PipelineRestClientConfig.googleApiClient(baseUrl)`
already exists and already handles lazy ADC resolution, so this is a one-line bean:

```java
@Bean
public RestClient secretManagerRestClient() {
    return googleApiClient("https://secretmanager.googleapis.com/v1");
}
```

Three calls total: `projects.secrets.create`, `projects.secrets.addVersion`, and
`projects.secrets.setIamPolicy`. (Contrast `google-cloud-storage`, which `pom.xml` documents as a
deliberate departure — a resumable upload is session handling, chunked transfer and retry semantics,
which is worth a library. Three JSON POSTs are not.)

**Unsatisfied requirements are a deploy precondition, not a manifest violation.** `ManifestValidator`
validates the *shape* of `requires:` — a valid env identifier, no collision with
`manifest.reserved-env-names` or the `SAILS_`/`SVC_` prefixes, no duplicates — because those are
properties of the manifest alone. Whether a requirement is *satisfied* depends on database state, so
it is checked in `PocDeploymentService` before the pipeline is triggered, and reported as a
deployment error listing every missing key at once. Keeping the two apart means a manifest doesn't
become invalid because of a row in another table.

## Data Model

**`pocs`** (migration `V16__add_pocs_runtime_service_account.sql`) gains:

| column | type | notes |
|---|---|---|
| runtime_service_account | TEXT NULL | The account id allocated for this POC, without the project suffix. Null for a POC created before this existed, which falls back to `poc-runtime-<environment>`. Stored rather than recomputed — see the Architecture Decision above. |

**`poc_container_env`** (new table, migration `V15__poc_container_env.sql`) — one row per bound
variable:

| column | type | notes |
|---|---|---|
| id | BIGINT GENERATED ALWAYS AS IDENTITY PK | |
| poc_id | BIGINT NOT NULL REFERENCES pocs(id) ON DELETE CASCADE | Cascade so a deleted POC leaves no binding rows. |
| container_name | VARCHAR(40) NOT NULL | Matches `poc_version_containers.name` width. |
| env_key | VARCHAR(128) NOT NULL | |
| kind | VARCHAR(16) NOT NULL | `CHECK (kind IN ('PLAIN','SECRET'))` |
| value | TEXT NULL | The plaintext, for `PLAIN` only. Always NULL for `SECRET`. |
| secret_id | TEXT NULL | Secret Manager secret id, for `SECRET` only. Always NULL for `PLAIN`. |
| updated_at | TIMESTAMPTZ NOT NULL | Shown in the portal instead of the value. |
| updated_by | VARCHAR(36) REFERENCES users(id) | Who last set it. Matches `users.id`, which is a ULID string, not a bigint. |

`UNIQUE (poc_id, container_name, env_key)` — one binding per key per container, so a key cannot be
both plain and secret.

```sql
CONSTRAINT ck_poc_container_env_kind CHECK (
  (kind = 'PLAIN'  AND value IS NOT NULL AND secret_id IS NULL) OR
  (kind = 'SECRET' AND value IS     NULL AND secret_id IS NOT NULL)
)
```

This constraint is the feature's central security guarantee, expressed where application code cannot
route around it: **a secret's value has no column to live in.**

`container_name` is intentionally *not* a foreign key to `poc_version_containers`. Bindings are set
against a POC before (and between) versions, and must survive a manifest that renames or removes a
container — an orphaned binding is inert and visible in the portal as "not declared by the current
manifest", which is better than a delete cascading from a version's container list.

## API Surface

All admin-guarded, consistent with the other POC mutation endpoints.

**`GET /pocs/{id}/environment`** — what the portal's Environment tab renders. Returns one group per
container declared by the POC's current manifest, merging the manifest's `requires:`/`env:` with
existing bindings:

```json
{
  "containers": [
    {
      "name": "backend",
      "variables": [
        { "key": "LOG_LEVEL",      "kind": "PLAIN",  "source": "MANIFEST_DEFAULT", "value": "info" },
        { "key": "DATABASE_URL",   "kind": "PLAIN",  "source": "ADMIN",   "value": "postgres://…",
          "updatedAt": "2026-09-05T10:11:12Z", "updatedBy": "alice@example.com" },
        { "key": "OPENAI_API_KEY", "kind": "SECRET", "source": "UNSET", "required": true }
      ]
    }
  ]
}
```

A `SECRET` variable never carries `value` — the field is absent, not masked. A `PLAIN` value is
returned, because it is not secret and hiding it would make the tab useless for the common case.

**`PUT /pocs/{id}/environment/{containerName}/{envKey}`** — body `{ "kind": "SECRET", "value": "…" }`.
Creates or replaces the binding. For `SECRET`, writes to Secret Manager, grants the runtime service
account access, stores `secret_id`, and discards the value. Returns the same shape as one entry
above — again with no `value` for a secret.

**`DELETE /pocs/{id}/environment/{containerName}/{envKey}`** — removes the binding and, for a
secret, deletes the Secret Manager secret. The next deploy stops passing it, since `--set-secrets`
replaces rather than merges.

`GET /pocs/{id}/manifest-preview` gains each container's `requires:` so the portal can show
unsatisfied requirements — and so the existing "won't build" banner can say *why* a deploy is
blocked before the admin clicks Deploy.

## Security Considerations

- **A secret value has nowhere to be persisted.** The `ck_poc_container_env_kind` CHECK constraint
  makes it a database error, not a code review finding.
- **It transits this API's memory once, on one request.** That request body must be excluded from
  any request/response logging, and the value field's Bean Validation messages must not echo the
  rejected value — Spring's default constraint violation messages include it, which would put a
  secret into an error response and the application log. This is the single most likely way this
  feature leaks and needs an explicit test.
- **Cloud Build sees only the name.** `--set-secrets=KEY=<secret-id>:latest` is stored permanently
  on the Build resource, as all step args are; a secret *id* there is harmless, which is the whole
  point of the indirection.
- **The Cloud Run env spec shows `valueFrom`, not a value** — so `gcloud run services describe`,
  which any project viewer can run, exposes the binding but not the secret.
- **Access is granted per secret, to the consuming POC's own runtime identity**, never project-wide
  and never to a shared one — so one POC's container cannot read another POC's secrets. This is what
  makes the derived, non-author-supplied naming scheme load-bearing rather than cosmetic, and it is
  the reason the per-POC service account above is a prerequisite rather than a refinement. An
  earlier draft of this section claimed the property while the platform still deployed every POC
  under one shared account, which would have made it false as written: the ids are derivable from
  the slug, a container can mint a token for the identity it runs as, and Secret Manager is a public
  API, so a shared identity means a shared pool. **This is a test, not an argument** — from one
  POC's container, ask Secret Manager for another POC's derived id and confirm the denial.
- **Admin overrides are subject to the same reserved-name rejection as manifests.** Without it an
  admin could set `PLATFORM_API_URL` and silently redirect a POC's JWKS lookup — a token-verification
  bypass dressed as a config change. The merge order (platform last, unconditional) enforces it, and
  the write endpoint should reject it outright so the admin gets an error rather than a value that
  is silently ignored.
- **Deletion.** Until POC deletion deletes its secrets, every deleted POC leaves paid, unaudited
  secrets in the project. Listed in Future Work and should not ship without at least the prefix
  cleanup.

## Implementation Phases

Ordered so each phase is independently shippable and useful.

**Phase 0 — a runtime identity per POC.** `V16__add_pocs_runtime_service_account.sql`; a
`ServiceAccountService` over the IAM REST API with idempotent `ensureRuntimeServiceAccount(poc)`
(create, then `setIamPolicy` granting the builder `serviceAccountUser`); `BuildService.deployStep`
naming the POC's account instead of the shared one; deletion on POC deletion; the Terraform role
changes. Shippable and worth shipping on its own — it turns the isolation between hosted POCs from
nominal into real, whether or not a secret is ever bound. Nothing in Phase 1 is safe without it.

**Phase 1 — secrets end to end.** `requires:` parsing (`ManifestParser`, `ManifestContainer`) and
shape validation (`ManifestValidator`); `V15__poc_container_env.sql`; `secretManagerRestClient` bean
plus a `SecretManagerService` (create / addVersion / setIamPolicy / delete); the three endpoints;
`--set-secrets` emission in `CloudRunDeployCommandBuilder` (it is
container-scoped, so it belongs with the existing per-container flags, not in `buildServiceArgs`);
the unsatisfied-requirements precondition in `PocDeploymentService`; the portal Environment tab.

**Phase 2 — plain admin overrides.** Same table, `kind = 'PLAIN'`, merged into `--set-env-vars`
ahead of manifest defaults. Small once Phase 1's storage, endpoints and UI exist, which is why it is
second rather than first despite being simpler.

**Phase 3 — reproducible rollback.** Snapshot each version's resolved plain env (and the secret
*versions* it ran with) onto `poc_versions`, so a rollback reproduces the environment as well as the
images. Mirrors what `manifest_yaml` already does for the manifest.

Portal work in Phase 1 is a fifth tab in `poc-settings-page` (`'general' | 'deployment' |
'visibility' | 'danger'` gains `'environment'`), rendered per container from the manifest preview,
following the existing tab and `?tab=` query-param pattern.

## Open Questions / Future Work

- **Orphaned secrets on POC deletion.** Deleting a POC cascades the binding rows but not the Secret
  Manager secrets. A prefix delete (`poc-<slug>-`) on POC deletion is the minimum; doing it as a
  background reconcile would also catch secrets orphaned by a failed unbind.
- **Rollback reproducibility.** `:latest` means a redeployed old version gets today's secret. Phase 3
  addresses it; until then this is a documented, accepted property, not an oversight.
- **No rotation flow.** Replacing a secret takes effect only on the next deploy; there is no "rotate
  and redeploy now" action, and no indication in the portal that a POC is running an older version of
  a secret than the one currently stored.
- **Audit trail is one row.** `updated_at`/`updated_by` record only the most recent change. If who
  changed a secret and when matters historically, this should emit into `activity-tracking.md`'s
  existing model rather than growing a private history table.
- **Binding a secret needs Secret Manager access.** The `--set-secrets` flags are emitted into the
  Cloud Build deploy step, so it is the build service account that needs to bind them and the
  runtime SA that needs to read them. A POC with no `requires:` is unaffected;
  `pipeline.executor=skip` remains the escape hatch. (This previously read as a caveat about the
  local executor needing a developer's own ADC to hold `secretmanager.admin` — that executor no
  longer exists, and every build now runs as the build service account.)
- **Should `requires:` subsume `env:`?** A `requires:` entry with a `default:` would express both,
  leaving one concept instead of two. Deferred deliberately: `env:` is already in the published
  `poc-platform-sdk` schema and in every existing manifest, so collapsing them is a breaking change
  that should not ride along with a new feature.
- **Per-container vs per-POC bindings.** Every binding is scoped to one container, which is correct
  (an env var set on the ingress is not visible to a sidecar — see `poc-manifest-deployment.md`) but
  means a value both containers need is entered twice. A "shared" scope that fans out to every
  container would fix the ergonomics; not worth it until a real POC needs it.

## Changelog

- 2026-09-10 — Draft to In Progress, with one blocking correction. This document's Security
  Considerations claimed that one POC's runtime identity cannot read another POC's secrets; every
  POC is deployed under the same shared `poc-runtime` account, so per-secret grants accumulate onto
  a single identity and the claim was false as written. A per-POC runtime service account is now
  Phase 0 and a prerequisite for binding any secret at all, which makes the claim true rather than
  dropping it. Also on this pass: the migration renumbered `V21` to `V15`, since the tree was at
  `V14` and the original number assumed work that never landed; `${secrets.X}` ruled out explicitly
  now that `env:` values carry placeholders, because an `env:` value is emitted through
  `--set-env-vars` and stored permanently on the Cloud Build resource; and the endpoints confirmed
  as staying admin-guarded, matching every other POC mutation endpoint rather than widening access
  alongside a new feature.

- 2026-09-07 — Initial draft. Written before implementation, per `docs/specs/README.md`. Arose from
  a design discussion about how a multi-container POC supplies per-container configuration: the
  existing pipeline injects platform-owned values and honours literal `env:` entries, but had no
  answer for a value that is secret or deployment-specific. The `--set-secrets` container-scoping
  and its replace-not-merge semantics were verified against `gcloud run deploy --help` before being
  designed around, rather than assumed — the `--port` merge behaviour that broke every
  previously-deployed multi-container POC on 2026-09-07 came from exactly that kind of unverified
  assumption.

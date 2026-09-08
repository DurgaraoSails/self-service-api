# POC Runtime Contract

## Status

Implemented

## Overview / Purpose

`docs/specs/poc-manifest-deployment.md` describes how a POC repo's `poc.yaml` becomes a
multi-container Cloud Run service. It stops at "the deploy command was submitted and gcloud
returned 0". This spec covers the part after that: **what the deployed service must actually look
like from a browser and from inside the instance** for a POC to be reachable, configured, and able
to verify a token.

It exists because the first real multi-container POC (`poc-integration-testbed`) deployed
"successfully" and could not be opened. Three separate defects between the deploy command and a
working iframe, each individually sufficient to break it — all three now fixed, and each kept below
because the reasoning is what stops them being reintroduced:

1. Cloud Run's ingress port is handed to the **sidecar**, not the ingress container, so external
   traffic reaches the wrong process.
2. Nothing injects the platform-owned environment variables (`POC_SLUG`, `PLATFORM_API_URL`,
   `PORTAL_ORIGIN`, `SVC_<NAME>_URL`, a sidecar's `PORT`) that both `poc-manifest-deployment.md`
   and `poc-bridge-contract.md` assume the pipeline sets. Reserving those names without ever
   setting them is the whole gap.
3. The service deploys `--no-allow-unauthenticated` on the premise that self-service-api proxies
   end-user traffic to a POC. **No such proxy exists**, and per `poc-hosting-architecture.md` none
   is planned — the portal embeds the POC's own URL directly. So the service is unreachable by the
   only client that ever calls it.

The unit of this spec is therefore the *runtime contract*: the guarantees a POC author may rely on
about the container they are deployed into, and the pipeline's obligation to provide them.

## Requirements

- The ingress container declared in `poc.yaml` receives all external HTTPS traffic, and receives
  Cloud Run's `PORT`.
- A sidecar is reachable from every other container in the service at a stable, discoverable
  address, without the POC author hardcoding a port.
- Every container knows which POC it is (`POC_SLUG`), where the platform API is
  (`PLATFORM_API_URL`), and which origin is allowed to frame it (`PORTAL_ORIGIN`) — all three
  supplied by the platform, never committed to the POC repo.
- A deployed POC is loadable in a browser from the portal, with no Google identity token, because
  that is the only way it is ever consumed.
- A `poc.yaml` key the pipeline does not act on must be visible to the person who wrote it, not
  silently discarded.
- Every build and deploy runs through Cloud Build. This was once "both executors must produce
  gcloud invocations that differ only in mechanism" — the local executor that requirement policed
  has been deleted precisely because its invocation *had* drifted, undetected, and one path cannot
  disagree with itself.

## Architecture Decisions

### The ingress container carries `--port`; sidecars carry none

Cloud Run's rule for a multi-container service is explicit: *"there is no default port for the
ingress container. You must explicitly configure the container port for the ingress container and
only one container can have the port exposed."* The container holding the exposed port **is** the
ingress container — that is how Cloud Run identifies it. There is no separate "which one is the
ingress" field.

The implementation had this exactly inverted. `ManifestValidator` forbids the ingress container
from declaring a `port:` (correct, as a *manifest-author* contract — it binds `$PORT`) and requires
every sidecar to declare one (also correct — that is how the ingress addresses it).
`CloudRunDeployCommandBuilder` then emitted `--port=` for precisely the containers that declared
one, i.e. **only sidecars**. For the testbed manifest that made the Spring Boot backend the ingress
container: every external request hit it, the Angular server never received one and never got
`$PORT`, and because Spring Security writes `X-Frame-Options: DENY` on every response — the 401
included — the portal's iframe rendered Chrome's "refused to connect".

The manifest contract is right and does not change. The **translation** to gcloud is what was
wrong:

- The ingress container gets `--port=<poc-runtime.ingress-port>` (default `8080`), a
  platform-owned value. Cloud Run then sets `PORT` in that container. **As built, a manifest that
  declares its own ingress port still wins**: `ManifestValidator` permits one rather than forbidding
  it, and `CloudRunDeployCommandBuilder.ingressPort` prefers it over the configured default. This
  spec originally had the validator forbid it, which would have rejected every multi-container
  `poc.yaml` already written against `poc-platform-sdk`'s schema — including the testbed's — for a
  value the platform can always supply anyway. Permitting both costs nothing and breaks nothing.
- A sidecar gets no `--port` flag at all. An earlier revision emitted gcloud's documented "unset"
  value, `--port=default`, to clear a stale port a service might be carrying from the inverted
  behaviour, since a deploy merges into the existing service rather than replacing it. gcloud
  rejects that outright — *carrying* the flag is what its own check counts, so two containers
  appeared to specify a port and every deploy failed before a request was sent (`Invalid value for
  [--container]: Exactly one container must specify --port or --use-http2`). The stale-port case it
  was defending against had never occurred: the service had never been successfully created at all.
  Both the fix and the theory it replaced came from real deploys, not review.

This is deliberately not "emit `--port` for whichever container declared one" with the validator
inverted to match. The manifest's meaning — *the ingress binds whatever the platform gives it; a
sidecar names the port it listens on* — is the right author-facing model and matches
`poc-platform-sdk`'s published schema that real POC repos are already written against.

### A sidecar's `PORT` is injected, because Cloud Run will not set it

Cloud Run injects `PORT` into the ingress container only. Once sidecars stop carrying `--port`,
nothing tells a sidecar which port to bind — and its declared `port:` is exactly that number. The
platform therefore sets `PORT=<declared port>` in each sidecar's environment.

This is why `PORT` was already in `manifest.reserved-env-names`: the reservation was written
against an injection that was never implemented. The same is true of the `SVC_` prefix.

### `SVC_<NAME>_URL` is how a container addresses a sidecar

Every container in a Cloud Run service shares one network namespace, so a sidecar is reachable at
`http://localhost:<port>`. Requiring each POC repo to hardcode that couples the repo to a port
number the manifest already states, and silently breaks if the port changes.

For every sidecar `<name>` with port `<p>`, the platform injects
`SVC_<NAME>_URL=http://localhost:<p>` into **every other container** in the service — name
uppercased with hyphens replaced by underscores (`chat-worker` → `SVC_CHAT_WORKER_URL`). A sidecar
does not receive its own variable; it knows its own port from `PORT`.

`poc-manifest-deployment.md` already described this behaviour as though it shipped. It did not.
This spec is where it actually gets built.

### `POC_SLUG`, `PLATFORM_API_URL` and `PORTAL_ORIGIN` are platform-owned, not repo-owned

All three are injected into every container, and all three are added to
`manifest.reserved-env-names` so a `poc.yaml` cannot clobber them.

- `POC_SLUG` — a POC's backend builds its expected audience as `poc:<slug>` from **its own
  configuration, never from the token** (`poc-bridge-contract.md`, step 6). Reading the expected
  audience out of the object being validated is a check that validates nothing. That control only
  works if the platform supplies the slug.
- `PLATFORM_API_URL` — the origin a POC fetches `GET /.well-known/jwks.json` from to verify
  tokens, and calls `/poc-files` against. **It must be an address reachable from Cloud Run.** A
  self-service-api running only on a developer's `localhost` cannot serve JWKS to a deployed POC;
  worse, inside a multi-container instance `http://localhost:8080` resolves to a *sibling
  container*, so the failure is a confusing wrong-response rather than a connection error.
- `PORTAL_ORIGIN` — required by `poc-bridge-contract.md`, which specifies it as "required runtime
  configuration, injected by the deployment pipeline", never derived from `document.referrer` or
  `location.ancestorOrigins`. It is both the `postMessage` `targetOrigin` and the origin a POC puts
  in its own `Content-Security-Policy: frame-ancestors`, so the JavaScript origin check and the
  browser-enforced embedding restriction cannot disagree.

### A deployed POC is public, and JWT verification is what protects it

`pipeline.allow-unauthenticated` defaulted to `false`, justified in-code by "the design is that
only self-service-api's proxy reaches a POC". That proxy does not exist in this repository and is
not planned: `poc-hosting-architecture.md` settles on the portal embedding a POC's own origin in an
iframe with Bearer tokens, specifically because `run.app` rules out cookie-based approaches. With
no proxy, `--no-allow-unauthenticated` means the service is reachable by nobody — the browser is
the only client, and it holds no Google identity token.

The default becomes `true`. This is a real change in exposure and is only acceptable because the
POC shell is already designed to be world-readable:

- The shell is **served publicly and unauthenticated by design**
  (`poc-bridge-contract.md`, Security Requirements) — it renders a skeleton and holds no secrets.
- Every request carrying data is authenticated by the POC's backend verifying an RS256 signature,
  `iss`, `exp` and `aud` against the platform's JWKS. That is the load-bearing control, and it is
  unchanged by who can reach the URL.
- `Content-Security-Policy: frame-ancestors <PORTAL_ORIGIN>` (now injectable, see above) stops a
  rogue site embedding the POC.

`BUILD_ALLOW_UNAUTHENTICATED=false` remains available for an environment that genuinely fronts POCs
with something else. `pipeline.grant-api-invoker` is untouched and stays off by default; it grants
self-service-api's own service account `run.invoker`, which a public service does not need.

### Service-level flags are built separately from container-level flags

gcloud parses every flag after the first `--container=` as scoped to that container, and rejects
anything it does not recognise as container-level with a usage error (exit code 2) — a regression
this repo has already hit once and documented on `BuildService.deployStep`.

`BuildService` respected that ordering; the local executor's `deploy` did the opposite, appending
`--region`, `--project`, `--service-account` and `--allow-unauthenticated` *after* the container
block. Any multi-container deploy through `executor=local` would have failed with exit code 2 — it
survived only because no multi-container deploy had run through it. That executor has since been
deleted outright, and this is a large part of why: a second path that mirrors the real one, but is
never exercised, records its drift as a latent failure rather than a caught one.

Rather than fixing the ordering in one place and trusting the next author to notice,
`CloudRunDeployCommandBuilder` now exposes **`buildServiceArgs`** alongside `buildContainerArgs`.
`BuildService.deployStep` emits its own credentials/region flags, then `buildServiceArgs`, then
`buildContainerArgs` — the ordering constraint is expressed by the API's shape instead of by a
comment. `buildServiceArgs` is also where `scaling:` lands (below), which is service-level and had
nowhere to go before.

### Every key `poc-platform-sdk`'s schema defines is now parsed, and none is silently dropped

`ManifestParser` read only `containers` and `resources`. The testbed's `poc.yaml` declares
`health`, `scaling` and `platform` blocks — written against `poc-platform-sdk`'s published schema —
and all three vanished without a word. A manifest author had no way to discover that half of what
they wrote did nothing.

- **`scaling: {min, max}`** → service-level `--min-instances`/`--max-instances`, emitted by
  `buildServiceArgs`.
- **`health: <path>`** → a per-container `--startup-probe=httpGet.path=<path>,httpGet.port=<port>`,
  where `<port>` is the ingress port for the ingress container and the container's own declared
  port for a sidecar. This is the container-level probe syntax gcloud documents
  (comma-separated `KEY=VALUE`, keys `initialDelaySeconds`, `timeoutSeconds`, `periodSeconds`,
  `failureThreshold`, `httpGet.path`, `httpGet.port`, `tcpSocket.port`).
- **`platform: {database, files}`** → parsed into a `Platform` record and stored with the manifest,
  driving no behaviour. Deferred until a per-POC database and per-POC file wiring actually exist.
  Parsing it now means a repo author's manifest doesn't change shape when that lands — and, unlike
  before, `ManifestService` can report that it was understood-but-inert rather than staying silent.
- **`repo:` on a container** → parsed and *rejected* by `ManifestValidator` with a specific message
  ("cross-repository containers aren't supported yet"). Every container still builds from the
  primary repo. A field that doesn't exist gives an author no way to learn why their intent was
  ignored; a field that's rejected does.

Any remaining unrecognised top-level or container key is collected and logged as
`unsupported manifest keys, ignored: …` at deploy time. Warned about, never rejected — rejecting
would break every POC repo already written against the fuller published schema, which is the
opposite of the compatibility this platform needs.

### The ingress container declares `--depends-on` for probed sidecars

Without ordering, all containers start in parallel and the ingress can proxy to a sidecar that
isn't listening yet — for the testbed that is a 502 from the frontend's `/api` proxy during every
cold start. Cloud Run's `--depends-on` fixes the ordering, with one constraint: **a container that
is depended on must have a startup probe**, or Cloud Run rejects the deploy.

So `--depends-on` is emitted on the ingress container listing exactly those sidecars that declared
a `health:` path (and is omitted entirely when none did). Tying it to `health:` rather than
emitting it unconditionally keeps a manifest that declares no probes deployable, instead of turning
an optional key into a required one.

## Data Model

No schema change. `poc_versions.manifest_yaml` already stores the manifest verbatim, so the newly
parsed `health`/`scaling`/`platform` values survive a redeploy without a new column — they are
re-derived by `ManifestService.resolveStored` from the same stored text. Manifest warnings are
surfaced in the application log at deploy time, not persisted.

`poc_version_containers.port` keeps its current meaning (set for a sidecar, null for the ingress) —
it records what the *manifest* declared, which is unchanged by this spec. The ingress port is a
platform constant and is deliberately not persisted per version.

## API Surface

No endpoint changes shape. `POST /pocs/{slug}/launch` is unchanged: it already returns
`{ token, expiresIn, launchUrl, pocId, slug }`, and `launchUrl` is the Cloud Run URL this spec makes
reachable.

New configuration (`application.yaml`):

| Property | Env var | Default | Notes |
|---|---|---|---|
| `poc-runtime.ingress-port` | `POC_RUNTIME_INGRESS_PORT` | `8080` | The port the ingress container binds. Platform-owned; a manifest cannot set it. |
| `poc-runtime.platform-api-url` | `SELF_SERVICE_API_URL` | `http://localhost:8080` | Injected as `PLATFORM_API_URL`. The env var is named for what you supply (this API's own URL); the injected name is what a POC sees (“the platform's API”). **Must be reachable from Cloud Run** — the local default is a development placeholder that cannot work for a deployed POC. |
| `poc-runtime.portal-origin` | `POC_RUNTIME_PORTAL_ORIGIN` | `${app.frontend.url}` | Injected as `PORTAL_ORIGIN`. Defaults to the portal URL this API already configures for email links. |
| `pipeline.allow-unauthenticated` | `BUILD_ALLOW_UNAUTHENTICATED` | `true` (**changed from `false`**) | See the security note above. |
| `manifest.reserved-env-names` | `MANIFEST_RESERVED_ENV_NAMES` | `PORT,POC_SLUG,PLATFORM_API_URL,PORTAL_ORIGIN` | Extended — a manifest may not set what the platform now injects. |

## Security Considerations

- **Making POC services public is the significant change here.** It is safe only because a POC's
  shell is designed to hold no secrets and every data path is gated by JWT verification against
  JWKS. A POC that skips the `aud` check accepts any platform-issued token, and being public turns
  that from "reachable by the proxy" into "reachable by anyone" — so the audience check in
  `poc-bridge-contract.md` step 6 moves from important to load-bearing, and `POC_SLUG` injection is
  what makes it implementable.
- `PORTAL_ORIGIN` injection closes the one gap `poc-bridge-contract.md` flags in its own open
  questions ("a deploy path that forgets it"). A POC can now both check `postMessage` origins and
  set `frame-ancestors` from a platform-supplied value rather than a committed one.
- `PLATFORM_API_URL` pointing at `localhost` inside a multi-container instance resolves to a
  sibling container rather than failing — a POC would fetch "JWKS" and get another container's
  response. Startup-time validation of this value is listed under Future Work; it is not a new
  exposure (verification fails closed) but it is a bad failure mode.
- No new secret handling. Platform-injected env vars are all non-secret: a slug, two public URLs,
  and localhost addresses. Nothing added here appears in a build log that did not already.
- `manifest.reserved-env-names` growing is a security control, not bookkeeping: without it a POC
  repo could set its own `PLATFORM_API_URL` and point token verification at a JWKS endpoint it
  controls.

## Open Questions / Future Work

- **`docs/specs/poc-manifest-deployment.md` describes behaviour that was never implemented** and
  must be corrected rather than left to contradict this document. Specifically it claims:
  `SVC_<NAME>_URL` synthesis and injection (built here, for the first time); `--depends-on=` and
  per-container `--startup-probe=` flags (neither is emitted); a `ManifestContainer.repo` field
  rejected with a clear message (the field does not exist); `Platform`/`Scaling` records (neither
  type exists); `platform.database`/`platform.files` "parse but do nothing" (they do not parse);
  and `DATABASE_URL` among the reserved env names (it is not). Its changelog is updated alongside
  this spec.
- **Nothing validates that `PLATFORM_API_URL` is reachable from Cloud Run.** The natural check is
  at deploy time — refuse to deploy with a loopback address — which would have caught this whole
  class of failure before a build ran. Deliberately not added yet: a developer running the app
  locally against Cloud Build may legitimately point this at a tunnel, and guessing which addresses
  are unreachable from Cloud Run risks blocking a working setup. The deploy warns instead.
- **`--startup-probe` and `--depends-on` are unit-tested but not deploy-tested.** The flag strings
  are asserted against gcloud's documented syntax; only a real multi-container deploy proves Cloud
  Run accepts them.
- **No end-to-end test exercises a real two-container deploy.** Unchanged from
  `poc-manifest-deployment.md`'s own open question, and it is exactly what let the port inversion
  ship: `CloudRunDeployCommandBuilderTest` asserted the *inverted* behaviour as correct, so the unit
  tests were green throughout. A green unit suite is not evidence here, and that is the single most
  valuable thing still missing.
- **`platform.database`/`platform.files` parse and validate but drive no behaviour**, pending the
  underlying platform capabilities.
- **Cross-repository containers** (`ManifestContainer.repo`) are rejected rather than supported.
- **The portal's half of the launch flow is tracked in `poc-bridge-contract.md`**, not here — this
  spec guarantees only that the POC is reachable and correctly configured when a token arrives. The
  `@sails/poc-bridge` library is being implemented against that spec alongside these changes;
  publishing it to GitHub Packages (and switching POC repos onto the published package rather than
  hand-rolled halves) is deliberately deferred.

## Changelog

- 2026-09-07 — Initial draft, written after `poc-integration-testbed` deployed successfully and
  could not be opened from the portal ("refused to connect"). Covers the ingress-port inversion,
  platform environment injection, public access, service-level flag ordering, and manifest keys
  that were silently discarded.

- 2026-09-08 — The env var feeding `poc-runtime.platform-api-url` is now `SELF_SERVICE_API_URL`;
  `POC_RUNTIME_PLATFORM_API_URL` and the `PLATFORM_API_URL` alias described in the entry below are
  both gone. One name meant two different things depending on which process read it — this API's
  own address on the way in, and "the platform's API" on the way out — which is exactly how the
  truncated value in `application-prod.yaml` went unnoticed. The *injected* variable is still
  `PLATFORM_API_URL`: that one is a published contract every POC and `poc-platform-sdk` reads, and
  "platform" remains the right abstraction from a POC's side. Any deployment setting the old names
  must be updated — there is no fallback, but a wrong value now surfaces as the localhost warning
  in `CloudRunDeployCommandBuilder` rather than silently.

- 2026-09-07 — Implemented, with three deliberate departures from the draft above, each recorded
  where it applies rather than only here. (1) A manifest may still declare its own ingress port —
  forbidding it would have rejected every multi-container repo already written against
  `poc-platform-sdk`'s schema. (2) A sidecar is given `--port=default` rather than no `--port`,
  because a deploy merges into the existing service and a service carrying a stale sidecar port
  would otherwise be permanently unrevisable. (3) `PLATFORM_API_URL` is still accepted as the env
  var name alongside `POC_RUNTIME_PLATFORM_API_URL`, so an environment configured before the
  property moved namespaces does not silently fall back to the localhost default — the exact
  failure this spec's own Security Considerations warns about. The draft's premise that
  `pipeline.allow-unauthenticated` still defaulted to `false` was already stale when written: that
  change had landed separately, so no work was needed for it.

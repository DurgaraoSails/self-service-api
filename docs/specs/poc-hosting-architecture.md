# POC Hosting & Integration Architecture

## Status

Draft — design decided. Two pieces of it are **implemented** as of 2026-09-03, built as Phase 2 of
`docs/specs/file-management.md`: `GET /.well-known/jwks.json` and `POST /pocs/{slug}/launch`, with
the `kid` header and audience separation that both depend on. They live there because file
management's only upload path is authenticated by the launch token, so that feature could not be
exercised at all until this token existed. Everything else here — the bridge library, the
design-token package, the Tier 1 state API, Tier 2 schemas — remains unimplemented.

## Overview / Purpose

Where POC user-interface code lives, and how an embedded POC learns who is using it. Four questions follow from that one choice:

- Should POC UI live in `self-service-portal`, or stay in its own repo?
- If it stays separate and is embedded via `<iframe>`, how does the POC know the signed-in user, and how is it prevented from being used outside the portal?
- How does a POC persist a user's work between launches, and how is that work purged when the trial ends?
- How do POCs that are more than one container fit the existing build pipeline?

Today `PocWorkspace` embeds `poc.appUrl` in an `<iframe>` with no credential passing of any kind, and `poc.appUrl` is never populated (see `poc-deployment-pipeline.md`), so no POC has ever been launched end to end. The catalog is database-driven (`poc-catalog.md`) and the pipeline builds one container image per POC repo (`poc-deploy-pipeline`).

## Requirements

- A POC must be usable only when launched from the portal by a signed-in, entitled user (active trial, POC `ACTIVE` and not hidden).
- A POC must know who the user is, and must match the portal's light/dark theme.
- A POC must be able to load files the user uploaded, i.e. call `self-service-api` on that user's behalf.
- A POC must persist that user's work across launches. Returning to a POC must not start from scratch.
- When a user's trial expires and their documents are purged, any per-user data a POC holds must be purged too.
- Adding or removing a POC must not require a portal rebuild or redeploy. This is not new — it is the stated purpose of the catalog feature (`poc-catalog.md`), already implemented.
- POC frontends standardize on Angular. POC backends may be any stack.
- All browsers must be supported, including Safari. The portal is shown to prospects on machines the organization does not control.
- Current scale: 5–20 POCs, single container each.

## Constraints

Environmental facts that eliminate otherwise reasonable designs.

**`run.app` is on the Public Suffix List, and custom domains are not planned.**
POCs and the portal deploy to `*.run.app` URLs. Custom domains were ruled out on the grounds that this is an internal-first tool where the address bar is not a product surface worth infrastructure work. Because `run.app` is a public suffix, `portal-xyz.run.app` and `poc-a-xyz.run.app` are separate *sites* to a browser, not sibling subdomains of a shared parent. A cookie with `Domain=.run.app` is rejected outright, so no cookie can be shared between the portal and a POC, and a POC iframe is a third-party context. Combined with the requirement to support Safari, whose ITP blocks third-party cookies by default, **any design depending on the POC iframe carrying a cookie is not viable.**

A load balancer alone would not change this, though it is easy to assume otherwise: same-site cookies require sibling subdomains, which require a controlled domain. An LB without one yields an IP address and no subdomain structure. The design below is deliberately indifferent to the question — it works on `run.app` today, and if a domain is adopted later nothing in it breaks.

**Cloud Run scales to zero and may terminate an instance at any time.**
A POC holding a user's session in process memory loses it between two clicks, regardless of what the browser sends. "Must not start fresh every time" is therefore a storage requirement, not a session requirement — satisfiable by a stable user identifier plus a durable store, and not satisfiable by browser state at all.

**The portal's tokens live in `localStorage`.**
`core/auth/token-storage.ts` keeps `ssp.accessToken` and `ssp.refreshToken` there, so any design placing a POC on the portal's origin grants POC JavaScript read access to them.

**`self-service-api` publishes no JWKS endpoint.**
`security/JwtKeyConfig.java` loads the RSA public key from a Secret Manager–mounted file directly into Spring Security's validator. Nothing exposes it over HTTP, so a POC backend in any other stack cannot verify a token this API issued.

**There is no file upload or storage API.**
No multipart handling, no GCS integration, no storage endpoints. The requirement that a POC load the user's uploaded files presumes a feature that does not exist; it is specified separately in `file-management.md` and is a prerequisite of this architecture rather than part of it.

## Architecture Decisions

### Composition happens at the container boundary, not the JavaScript boundary

**A POC is one Git repository, one container, one Cloud Run service, embedded as an iframe.** The repo holds its own Angular application — its own build, its own Angular version — alongside its backend in whatever stack that team prefers, a single `Dockerfile` serving both, a `poc.yaml`, and two platform dependencies: the bridge library and the design-token package. Adding a POC to the portal is an admin completing a form; withdrawing one is `POST /pocs/{id}/hide`. Neither touches portal code.

All three candidate models can add a POC without redeploying the portal. What separates them is where the seam between portal and POC sits. Lazy-loaded routes put it at **compile time**, so the POC ends up inside the portal bundle and adding one becomes a portal pull request and release. Module Federation puts it in the **JavaScript runtime**, sharing an Angular instance, the DOM, and `localStorage` across every POC. An iframe puts it at the **network boundary**, where nothing is shared but a message channel.

The iframe seam is the coarsest, and that is the argument for it: it is the only one that survives twenty repositories owned by teams on different release cadences. It also matches a commitment already made in practice, since `poc-deploy-pipeline` deploys one container per POC with independent semver, tagging, and rollback.

"Modular frontend" covers two different things, and only one is rejected. Each POC's own Angular application should be internally modular; that is ordinary application structure. What is rejected is POCs as modules *of the portal*.

The bridge library is what makes the iframe boundary acceptable — without it, the usual objection to iframes would hold. An unassisted iframe is a mismatched box with its own scrollbar, no theme, no identity, and no deep-linking. Each of those is addressed at the message channel rather than by abandoning the boundary: the design-token package renders the POC in the portal's visual language; a `resize` message lets the portal size the frame instead of nesting scrollbars; a theme message propagates the toggle live; `navigate` messages let the portal mirror POC state into its own URL; a `ready` signal lets the portal show a skeleton over Cloud Run cold starts; and the token handoff supplies identity.

### POC UI code stays in its own repository, one repo per POC

Co-location in `self-service-portal` would reverse architecture already in the ground. `poc-catalog.md` exists specifically so POCs can be added, edited, and removed without a frontend deploy; co-locating POC UI would make every POC change a portal release. The entire `poc-deploy-pipeline` service is built on one repo → one image → independent semver per POC, with `deploy-new-version` and `check-updates` operating per POC, all of which becomes dead code. And since POC backends may use any stack, the frontend would be the only half of a POC living elsewhere.

The concern that originally prompted the question — stale POC code accumulating when a POC is temporarily withdrawn — is real but secondary, and is already solved without touching code by `POST /pocs/{id}/hide`.

An NgModule- or lazy-route-per-POC structure inside the portal repo is rejected for the same reasons. Angular 21 standalone components make the practical unit a lazy-loaded route rather than an `NgModule`, but the distinction does not matter: either way the POC compiles into the portal bundle.

### Embedding model: public shell, Bearer-authenticated data plane, one origin per POC

Each POC keeps its own origin. Its static shell — HTML, JS, CSS — is served without authentication, on the grounds that a JavaScript bundle is code, not data. Every request that returns or mutates *data* carries `Authorization: Bearer <poc-scoped token>` on a JS-initiated `fetch`. The portal mints that token through `POST /pocs/{slug}/launch` and hands it to the iframe over `postMessage` after a ready handshake, so it never appears in a URL, a server log, a `Referer` header, or browser history.

This is the only candidate that uses **no cookies at all**, which makes the `run.app` third-party context irrelevant and behaves identically in every browser including Safari. It preserves a real origin boundary between the portal and each POC, and between POCs. Streaming responses (SSE and WebSocket, common in LLM demos) reach the POC directly with no proxy hop to buffer them. It requires no per-POC infrastructure as the catalog grows.

Its cost is decentralized authentication: each POC validates a JWT against `self-service-api`'s JWKS rather than a gateway doing it once centrally. On the frontend the bridge library absorbs this entirely. On the backend it is roughly twenty lines per stack, which is what the template repository and the pipeline conformance check exist to keep honest.

One consequence is that POC Cloud Run services become **publicly reachable** for their shell, a deliberate departure from the private, gateway-invoked-only posture assumed in `poc-deployment-pipeline.md`.

### The portal is the token refresh authority; the POC never holds a refresh token

POC-scoped tokens are short-lived. When one nears expiry the bridge library asks the parent portal over `postMessage` for a fresh one, and the portal — which holds the user's real session — calls `/pocs/{slug}/launch` again. The POC never receives, stores, or can use a refresh token, and portal logout stops POC access at the next refresh without any revocation infrastructure.

### A POC's frontend and backend may both call `self-service-api` with the POC-scoped token

Restricting these calls to POC backends would protect nothing. CORS is already `allowedOriginPatterns("*")` with `allowCredentials(false)` (see `jwt-authentication.md`), which is safe here precisely because this design uses no cookies, so no origin list needs widening. And the POC-scoped token is delivered to the browser by `postMessage` under the embedding model above, so it is browser-resident by construction.

The security properties that matter come from the token's claims rather than from which process presents it: a POC cannot name a user or another POC in any request (see `file-management.md`), and the token remains short-lived, audience-scoped, and refreshable only through the portal. Allowing direct browser-to-API calls removes two hops from every upload.

### POC token verification uses a JWKS endpoint and a `kid` header

A public `GET /.well-known/jwks.json` serves the public key as a JWK Set. Nimbus JOSE+JWT is already on the classpath, since Spring Security's resource server depends on it, so this is an `RSAKey` built from the existing `RSAPublicKey` bean and exported public-key-only — no new dependency, and no departure from the restraint about SDK sprawl established in `poc-deployment-pipeline.md`. It must be added to `SecurityConfig`'s `permitAll()` matchers rather than merely marked public in OpenAPI; `poc-catalog.md` records this exact mismatch causing a failure before, because OpenAPI's `security: []` documents intent while Spring Security enforces its own `.anyRequest()` fallback.

Tokens must be issued with a `kid` header in the same change. They carry no key id today, which is invisible while exactly one key exists and blocks rotation the moment a second is needed, since a JWKS consumer selects its verification key by `kid`. Adding it now costs one line; adding it after POCs are live requires every cached key set and every issued token to turn over together.

The endpoint is served with a `Cache-Control` max-age. Standard JWKS clients cache the set and refetch on encountering an unknown `kid`, which is what makes future rotation a non-event: publish both keys, begin signing with the new one, let caches catch up, retire the old.

### Session continuity comes from a stable user identifier plus durable storage

Because Cloud Run instances are disposable, a POC keys its per-user state on the `sub` claim carried in the launch token and persists it in a durable store. The same user launching the same POC a week later presents a different token with the same `sub` and finds their work. No cookie and no browser storage participates, which is what decouples the "don't start fresh" requirement from the cookie problem entirely.

### Per-user POC data lives in a platform-owned store, in three tiers

POCs do not provision their own databases or buckets. The platform owns the storage so that trial-expiry purge is guaranteed centrally rather than depending on every POC team implementing a purge path correctly — one omission would silently retain personal data, and the platform would have no way to know.

A single storage mechanism does not fit, because POCs differ in kind rather than degree. A realistic RAG document-Q&A POC needs four distinct things at once: the user's uploaded documents (the files API, not POC storage at all), conversation history and "where I left off", indexing metadata, and chunk embeddings for similarity search. That last one cannot be served by a key-value API, which is what forces more than one tier.

**Tier 0 — persist nothing, rebuild from platform files.** The POC fetches the user's documents through the files API at session start and works in memory. Purge is free because nothing survives, and there is no store to provision. Cold start costs seconds at demo-sized document volumes. This tier is named explicitly and offered first, because teams reach for a database reflexively; for summarizers, extractors, and Q&A over a handful of files it is sufficient and strictly safer than the alternatives.

**Tier 1 — platform state API.** Per-`(user, POC)` documents read and written through `self-service-api`, scoped by claims on the POC token. Purge is a single prefix delete. POC teams receive no database credentials and hold zero database connections. This covers what "must not start fresh every time" usually means in practice: chat history, drafts, saved outputs, wizard progress.

**Tier 2 — Postgres schema per POC, with pgvector.** Full relational access, POC-owned migrations, and the only tier that serves similarity search. Granted on request rather than by default.

The reason Tier 2 is the exception is connection count rather than correctness. `self-service-api` runs Hikari at 5 connections against Neon; twenty POCs each holding a comparable pool exceeds what the database tier sustains without a connection pooler.

**Tier 2's purge guarantee is enforced against the live database, not against POC code.** The standing objection to per-POC schemas is that central purge depends on a `user_id` convention nothing enforces, where a single omission silently retains personal data. Reviewing migrations does not fix this; a scheduled check querying `information_schema.columns` for every table in every `poc_*` schema, failing loudly when one lacks `user_id TEXT NOT NULL`, does. It inspects the database as it actually is rather than trusting a code review, so a POC team cannot create a non-purgeable table without setting off an alarm. Purge then generates `DELETE FROM <schema>.<table> WHERE user_id = $1` across every table it discovers.

### Purge is weighted toward object storage, but the database stays in scope

The substantive volume of user data is uploaded files in GCS; the database holds comparatively little. Purge engineering effort is therefore aimed at object storage, and `file-management.md` is where that work lives.

The database is not exempt, because sensitivity is not proportional to volume. Tier 1 conversation history quotes document content directly — a saved turn reading "the indemnity clause caps liability at $2M" retains the contract's substance after the PDF is gone — and Tier 2 embeddings are derived from document text closely enough that reconstruction from vectors is an active research area. Treating either as out of scope would leave a purge guarantee that is technically false.

Honouring it costs nothing under the tiering above: Tier 0 has nothing to purge, Tier 1 is a prefix delete, Tier 2 is the generated `DELETE` sweep. The distinction is one of engineering emphasis, not of obligation.

### Platform deliverables

**A shared bridge library, versioned and owned by the platform team.** The portal ↔ POC contract is small but must be identical everywhere: the ready handshake, token handoff and refresh, theme propagation, height and resize signalling, and session-expiry notification. It also ships the file uploader component (see `file-management.md`), so upload lives inside a POC's own layout without any POC team writing multipart handling. A POC author's integration work becomes installing a package and calling one initializer. The message protocol, the frontend integration points, and the token verification a POC backend must perform are specified in `docs/specs/poc-bridge-contract.md`.

**A template repository and a versioned design-token package — both, not either.** These solve different halves of one problem and are often conflated. A template repository gives day-one structure (Dockerfile, `poc.yaml`, `$PORT` handling, health endpoint, bridge wiring, CI) but is a *fork*, so later improvements never reach POCs already created from it. A versioned design-token package does propagate on version bump. Theme is additionally pushed at runtime through the bridge rather than baked in, so a POC follows the portal's toggle live.

**A conformance check in the deployment pipeline.** The template's contract decays unless something enforces it. `poc-deploy-pipeline` fails the build when a repo lacks `poc.yaml`, a Dockerfile, a health endpoint, or binds a port other than `$PORT`.

**`poc.yaml` as the POC's declaration of shape.** A manifest at the repo root declaring `components[]`, each mapping to one image and one Cloud Run service, with exactly one marked externally reachable and the rest on internal ingress. For today's single-container POCs this is a one-entry file, but it generalizes the hardcoded `app` image component flagged as a gap in `poc-deployment-pipeline.md`, and it answers both "how do frontend and backend coexist in one POC repo" and "what if a POC is itself microservices" — the same question. Defined as a contract now so the template ships with it; multi-component support is deferred.

### Packages are published to GitHub Packages

Artifact Registry offers an npm repository and would keep everything inside GCP, which is tidier on paper. It is rejected because it requires another GCP IAM binding, and IAM grants are this project's demonstrated bottleneck — `poc-deployment-pipeline.md` records five still blocked awaiting an IT-granted role. GitHub Packages requires no GCP IAM, and the `github-token` secret it authenticates with already exists in Secret Manager and is already injected into Cloud Build for the pipeline's repository clone. The incremental infrastructure is an `.npmrc` in the POC template rather than a new dependency on what is already blocking this project. A paid npm organization adds cost and an external account for no capability these packages need.

Two packages rather than one: `@sails/design-tokens` (CSS custom properties, no framework dependency, so it survives POC frontends ever diversifying past Angular) and `@sails/poc-bridge` (the Angular library, depending on the tokens package). Both live in `self-service-portal` as workspace libraries and publish from its CI — see `poc-bridge-contract.md` for why the bridge is not housed separately from the portal half of the protocol it implements.

### Rejected alternatives

**Gateway as authenticating reverse proxy (path-routed, shared origin).** One `self-service-gateway` service fronting `gateway/p/<slug>/*` and proxying to private POC services was the original plan's direction, and it is genuinely attractive: POC authors write no auth code, and adding a POC touches no infrastructure. It fails on two counts. A proxied web application needs a cookie, because the browser issues document and subresource requests — `<script src>`, stylesheets, images — that no JavaScript controls and that cannot carry a Bearer header; that cookie is third-party under `run.app`, requiring CHIPS partitioning that Safari will not reliably honour. And all POCs would share the gateway's origin, so one POC's JavaScript could reach another's storage. **This becomes the best option if custom domains are ever adopted**, where the cookie is same-site and each POC keeps its own subdomain; it is recorded so that reversal is a deliberate re-decision rather than a rediscovery.

**Module Federation, POCs as runtime-loaded Angular remotes.** Technically viable given POC frontends standardize on Angular, and like the chosen model it needs no cookies. Rejected for three reasons: it forces Angular version lockstep between the portal and every POC, a coordination cost on every Angular major across 5–20 independently-owned repos; it provides no isolation, since POC code shares the portal's JavaScript runtime, DOM, and `localStorage`, meaning a POC can read the portal's tokens and an unhandled POC error can break the portal shell; and it splits the build pipeline, since a POC frontend becomes a static bundle rather than a container. Worth reconsidering for a single POC that must feel genuinely native to the portal, not as the default.

## Data Model

No changes to `pocs` or `poc_deployments`. Two additions are implied by the storage tiers and are designed in the specs that own them:

- Per-`(user, POC)` file metadata — `user_files`, see `file-management.md`.
- Per-`(user, POC)` Tier 1 state — shape to be designed with the state API.

## API Surface

Additions to `self-service-api`:

- **`GET /.well-known/jwks.json`** — public. Publishes the RSA public key so POC backends in any stack can verify tokens.
- **`POST /pocs/{slug}/launch`** — authenticated, trial-gated. Validates entitlement and mints a short-lived POC-scoped token (`aud: poc:<slug>`, `sub: <userId>`, plus display name and theme), returning it with the launch URL. This is the single point where "may this user open this POC" is decided, and the endpoint the bridge's refresh flow re-calls.
- **The POC-facing platform API** — file endpoints are specified in `file-management.md`, which establishes this as a versioned surface rather than a one-off. The Tier 1 state endpoints belong to the same surface, with the same token and the same claim-derived scoping.
- **A purge path** reaching POC-held data when a trial expires; the trigger is specified in `file-management.md`.

## Security Considerations

- The POC never receives the portal's access or refresh token — only a separate, audience-scoped, short-lived token, useless against any other POC. It never receives a refresh token at all.
- A POC's static shell is publicly reachable. This is a deliberate acceptance: a JavaScript bundle is not sensitive, and every data path behind it is authenticated. Opening a POC URL directly yields a shell with no token and therefore no data.
- Same-origin embedding — Module Federation, or POC-to-POC under a shared-origin gateway — would let POC JavaScript read the portal's `localStorage`, where access and refresh tokens currently live. This is the concrete reason origin isolation is worth paying for even among internal teams.
- `Content-Security-Policy: frame-ancestors` restricted to the portal origin is set on POC responses, so a POC page cannot be framed by an arbitrary third-party site. The template makes this the default.
- `postMessage` in both directions must validate `origin` explicitly and use an explicit `targetOrigin`, never `*`.
- CORS remains `allowedOriginPatterns("*")` with `allowCredentials(false)`. That is defensible here because the API stays a pure Bearer-token API with no cookies anywhere, so an open origin list carries no CSRF exposure and POC origins need no allowlisting.
- Trial-expiry purge is a data-protection obligation, not a cleanup nicety. If POC-held data is not reliably purged when portal-held documents are, the platform's purge guarantee is false.

## Open Questions / Future Work

- **`/pocs/{slug}/launch` is the only POC endpoint keyed by slug.** Every other one — `hide`,
  `deploy`, `versions`, `deployments` — takes the numeric id, so the two now sit side by side under
  the same path prefix. Slug was kept because it is what the token's audience names and what a POC
  identifies itself by, but the inconsistency is real and the portal holds the id already. Worth
  settling before POC-facing endpoints multiply.

- **Cold-start UX.** Scale-to-zero means clicking Launch on an idle POC waits for a container start. Options are warming the POC from the `/launch` call while the portal shows a skeleton, or `min-instances=1` for a small set of featured POCs at a known cost.
- **Per-POC secrets.** No convention exists for a POC holding its own credentials, and one is needed before any POC calls an external model provider.
- **`self-service-gateway` is superseded but deliberately retained.** With no custom domain, no load balancer, and no proxying, the service account and its blocked invoker IAM binding in `self-service-terraform` have no purpose under this design. Removal was considered and deferred: it costs nothing to leave in place, and keeping it preserves the option should custom domains ever be adopted, at which point the gateway model above becomes the better answer.
- **Artifact Registry and Cloud Run revision retention.** Neither has a cleanup policy. At this scale it accumulates quietly rather than dramatically, but it is unbounded as written.
- **Multi-component POCs.** `poc.yaml` defines the contract, but the pipeline still builds exactly one image per POC. The work to build and deploy `components[]` is deferred until a POC actually needs it.

## Changelog

- 2026-09-03 — **Implemented** the JWKS endpoint, the `kid` header, `POST /pocs/{slug}/launch` and
  audience separation. Entitlement decisions the design left implicit: a hidden POC is a 404 rather
  than a 403, since distinguishing them tells an unentitled caller that a POC by that name exists
  and is merely withheld — which is what hiding one is meant to prevent — and admins are not
  exempted, because a withdrawn POC should not be launchable while withdrawn. A POC that has never
  deployed (`appUrl` null) is a 409, not a 404: it is real, and the caller did nothing wrong.
- 2026-09-03 — `GET /.well-known/jwks.json`, the `kid` header on issued tokens, `POST
  /pocs/{slug}/launch`, and audience separation moved into `file-management.md`'s implementation
  plan as its Phase 2, rather than waiting for a branch of their own. The reason is a dependency
  that only became obvious once file management was sequenced: its upload endpoint is deliberately
  POC-facing only, so without a POC-scoped token there is no way to put a file into the system.
  Audience separation is called out explicitly because issuing a second token class against the
  same key, with no `aud` validation anywhere in `SecurityConfig`, would otherwise let a POC token
  reach portal endpoints such as `/users/me`.
- 2026-09-02 — Initial draft.

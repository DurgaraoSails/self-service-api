# POC integration guide and AI implementation brief

Contract snapshot: 8 September 2026. Portal bridge protocol: **v1**.

Give this entire file to the developer or coding agent working in the POC repository. It contains the integration requirements; access to the platform repositories is helpful but is not required to understand the wire contract. Example domains and tokens are placeholders, not working credentials.

This is the **full** contract, including the POC-scoped JWT and the portal bridge. A team that only wants its POC hosted and launchable — no authentication, no bridge, no file or database integration — has a shorter path: the in-portal onboarding guide at `/docs/host-poc`, specified in `docs/specs/poc-onboarding-check.md` (the readiness checker) and in `self-service-portal` at `docs/specs/poc-onboarding-guide-page.md` (the guide itself). Start there, and come back to this file when the POC needs to know who its user is.

## 1. Instructions to the implementing coding agent

Adapt the existing POC to the SAILS self-service platform. Implement the changes in the POC repository, preserving its business functionality and existing framework unless a change is necessary for integration. Do not stop at a plan or at adding frontend token decoding: complete frontend, backend, runtime configuration, container packaging, and relevant tests.

First inspect the repository instructions, application entry points, API routes, existing authentication, persistence, HTTP clients, Dockerfiles, and build scripts. Identify every route that returns user data, executes work, uploads/downloads files, or incurs model/API costs. Apply authentication and ownership checks to all of them.

Use the requirements below as the target contract. Read MUST as required, SHOULD as a recommended implementation choice, and OPTIONAL as conditional on the POC's features. This guide includes POC-side defensive requirements as well as descriptions of current platform behavior; it does not claim the platform enforces all POC implementation requirements automatically.

Work independently on everything the repository makes clear. Keep unknown environment values as explicit runtime configuration; never invent real domains, credentials, package versions, or issuer values. Ask the platform contact only for missing values or product decisions that block completion. Report remaining external prerequisites separately from code that is complete. Do not weaken verification to make an unavailable environment appear to work.

Deliver:

1. Working POC-side implementation and a root `poc.yaml`.
2. Reproducible build/run instructions and a configuration example containing no secrets.
3. Automated authentication, bridge, and data-isolation tests, plus container checks appropriate to the application.
4. A completed acceptance checklist from section 13, with actual commands/results and explicit untested items.
5. A short integration handoff listing required platform configuration and any outstanding infrastructure prerequisites.

Do not modify the platform's authentication endpoints or require a portal release to accommodate a different message shape. If a business requirement needs a new claim or protocol operation, document that gap for the platform team instead of silently inventing an incompatible extension.

## 2. Architecture and responsibility boundaries

The portal authenticates the user. It embeds the POC's own HTTPS URL in an iframe on a separate origin. The iframe receives a short-lived, POC-scoped JWT through `postMessage`. The POC frontend sends that token to its backend using an HTTP Bearer header. The backend verifies it using the platform's public JWKS and applies user ownership checks.

```text
User signs into portal
  -> portal calls POST /pocs/{slug}/launch with its PORTAL access token
  <- platform returns a POC token and launch URL
  -> portal loads the POC's public shell in an iframe
  <- POC sends poc:ready
  -> portal sends portal:session with the POC token
  -> POC calls its data API with Authorization: Bearer <POC token>
  -> POC backend verifies signature, issuer, audience, expiry and ownership

POC token nears expiry / authenticated request returns 401
  -> POC sends poc:refresh
  -> portal calls the launch endpoint again
  -> portal sends a replacement portal:session without reloading the iframe
```

| Responsibility | Owner |
|---|---|
| Registration, OTP/login, portal access and refresh tokens | Platform API and portal |
| Decide whether the user can launch a visible, deployed POC | Platform API |
| Mint/sign POC JWTs and publish public keys | Platform API |
| Verify JWTs on the POC's data/compute endpoints | POC backend |
| Handshake, in-memory token lifecycle, authenticated requests, UI state | POC frontend |
| Scope POC-owned records and operations to the verified user | POC backend |
| Container builds, Cloud Run service, platform runtime variables | Platform deployment implementation |

The POC MUST NOT implement another login flow for portal users, request the portal refresh token, receive the signing private key, or use a Google identity token as the user's POC credential. The deployed shell is publicly reachable; JWT verification protects data and operations. A CSP or an iframe check alone is not API authentication.

## 3. Inputs and runtime configuration

Obtain the following environment-specific values from the platform contact. The same built image should work across environments with different runtime settings.

| Value | Source and required interpretation |
|---|---|
| `POC_SLUG` | Platform-injected, exact catalog slug. Expected JWT audience is `poc:` followed by this value. Never derive it from an incoming token. |
| `PLATFORM_API_URL` | Platform-injected API base URL, reachable from the deployed backend; also browser-reachable if the frontend calls platform file endpoints. Append `/.well-known/jwks.json` for public keys. |
| `PORTAL_ORIGIN` | Platform-injected bare origin, e.g. `https://portal.example.com`, with no path, query, or fragment. Used for message validation, `targetOrigin`, and CSP. |
| Expected JWT issuer | Must match the platform's configured `jwt.issuer` / `JWT_ISSUER`. Current API default is the literal string `self-service-api`, **not** the API URL. Confirm the deployment's value. |
| `PORT` | Process listening port. Supplied to ingress by Cloud Run and injected for sidecars from their manifest port. Bind to `0.0.0.0`, using this value. If the application already reads a different name, alias it in `poc.yaml` with `${self.port}` rather than changing the code — see section 9. |
| `SVC_<NAME>_URL` | Platform-injected server-side URL for another sidecar, e.g. sidecar `backend` becomes `SVC_BACKEND_URL=http://localhost:8081`. Hyphens become underscores and names are uppercase. Alias it to an existing name with `${services.backend.url}` — see section 9. |
| Business-service secrets | POC-specific server-side configuration arranged with the platform contact. Never embed in browser assets or literal manifest entries. |

**Issuer configuration gap:** the current deployment code injects the slug, platform API URL, and portal origin, but does not automatically inject a JWT issuer variable. In the POC, define a backend setting such as `POC_EXPECTED_ISSUER`, document it, and have the platform contact provide the confirmed value. For the current default deployment, a non-secret manifest literal `POC_EXPECTED_ISSUER: "self-service-api"` is suitable after confirmation. This is a POC-defined setting, not an existing automatically injected platform variable.

Validate required values at startup. Production URLs must use the intended HTTPS hosts; allow HTTP only for explicit local development. Reject wildcard/missing origins and malformed configuration. A deployed backend's `localhost` is its own container network, not the developer's laptop or the platform API.

### Exposing safe configuration to the browser

Container environment variables do not automatically become Angular/React/Vue browser configuration. Implement a public runtime JSON endpoint/file, loaded before the bridge starts, for example:

```json
{
  "portalOrigin": "https://portal.example.com",
  "platformApiUrl": "https://platform-api.example.com",
  "pocApiBasePath": "/api"
}
```

`/runtime-config.json` and these JSON property names are suggested POC-local conventions, not platform endpoints. Generate/serve the values from the runtime environment. Serialize JSON safely, expose only an explicit allowlist, and avoid stale caching. Never dump the process environment. Backend secrets, service credentials, and tokens MUST NOT appear here.

For a frontend ingress plus backend sidecar, proxy browser `/api/...` requests through the ingress to `SVC_BACKEND_URL`. Preserve `Authorization`, HTTP methods, bodies, status codes, and streaming behavior where used. Decide and test whether `/api` is preserved or stripped. Do not send a `localhost` sidecar URL to the browser: there it addresses the user's computer.

## 4. POC JWT contract and backend verification

### Token shape

Tokens are signed with RS256 and carry a `kid` header. The platform's configured POC lifetime currently defaults to 15 minutes; do not hardcode that duration in clients.

| Header/claim | Current shape and meaning |
|---|---|
| `alg` | `RS256`; backend must explicitly allow this algorithm |
| `kid` | Key identifier matching an RSA public key in the platform JWKS |
| `iss` | Configured platform issuer; currently defaults to `self-service-api` |
| `sub` | Platform user ID string; use as the authenticated user's identity |
| `aud` | POC audience, `poc:<slug>`; JWT libraries may expose this as a string or a list |
| `pocId` | Numeric catalog POC ID; platform file storage uses this stable ID |
| `name` | Display name, with first/last name fallback |
| `theme` | JWT value is currently uppercase `LIGHT` or `DARK` |
| `iat`, `exp` | JWT NumericDate values: seconds since Unix epoch |
| `jti` | Unique token ID; not a refresh token or a server-side session lookup key |
| `trialEndDate` | Optional NumericDate, also in seconds |

POC tokens currently contain **no email, roles, tenant ID, or refresh token**. Do not require these claims, infer admin authority from a user's name, or assume portal administrators receive extra POC privileges. If the POC requires tenant-based authorization, raise that as a platform contract requirement; `sub` alone does not supply tenant membership.

### Verification on every protected request

Use an established JWT/JWKS library supported by the POC's existing stack. Configure its actual verification APIs; a base64 decode helper is not verification.

1. Require `Authorization: Bearer <token>` on all data and compute routes. Reject missing, malformed, or multiple ambiguous credentials.
2. Obtain keys only from the configured `PLATFORM_API_URL` plus `/.well-known/jwks.json`. Do not follow a token-supplied `jku`, `x5u`, issuer URL, or arbitrary key location.
3. Verify the RS256 signature using an appropriate RSA public key selected by `kid`. Reject unsigned tokens and other algorithms.
4. Require an exact issuer match against trusted server configuration.
5. Require the intended POC audience based on trusted `POC_SLUG`. Reject tokens with no POC audience or a different POC audience. Normalize string/list forms; the issued contract contains a single POC audience. Reject ambiguous multiple-POC audience values.
6. Require a valid, unexpired `exp` and nonempty `sub`; check `nbf` if present using the library's validation. Use only a small, explicit clock tolerance and keep deployed clocks synchronized.
7. Validate the required POC claim types, including a positive integral `pocId`. Never take the caller's effective user ID from a request body, query parameter, or unsigned message payload.
8. If `trialEndDate` is present, deny protected operations when current time is at or after it. Absence means there is no claim-based trial restriction; do not treat absence as an error or grant administrative privileges. A malformed present value must fail closed.
9. Apply resource ownership and business authorization after successful authentication.

Cache the JWKS with bounded lifetime and refresh on an unknown key ID, using request coalescing/rate limits and network timeouts. Support key rollover without hardcoding one public key forever. If the key cannot be resolved or verified, deny the request; do not accept an unverified token during a JWKS outage. Do not fetch keys separately for every request when an appropriate cached key is available.

Suggested middleware structure (pseudocode, not a library API):

```text
expectedAudience = "poc:" + configuredPocSlug
jwks = cachedRemoteKeySet(configuredPlatformApiBase + "/.well-known/jwks.json")

on protected request:
    rawToken = requireSingleBearerCredential(request)
    claims = verifyJwt(rawToken, jwks,
                       algorithms=["RS256"], issuer=configuredExpectedIssuer,
                       audience=expectedAudience, require=["exp", "sub"])
    validatePocClaimTypesAndAudience(claims)
    if claims.trialEndDate exists and now >= claims.trialEndDate:
        return forbiddenTrialExpired
    request.identity = verifiedIdentity(claims.sub, claims.pocId)
    authorizeResourceFor(request.identity)
    executeHandler()
```

Return `401` for invalid/missing/expired credentials. Return `403` for an authenticated user whose trial or business authorization disallows the action. Cross-user resource lookup should return `404` or a consistent authorization denial without exposing the other user's data. Use API responses, not HTML login redirects. An upstream verification service outage may be reported as a bounded service error, but must never result in authorized access.

**Revocation boundary:** POC JWTs are locally verified bearer credentials. Portal logout prevents future renewal and should end the iframe UI, but an already issued token is not instantly revoked server-side; it can remain valid until expiry (or trial cutoff). Do not promise immediate global token revocation. If the POC requires that guarantee, it needs an additional agreed platform design.

## 5. Portal bridge: complete v1 wire contract

Every message is an object with a string `type` and numeric `v: 1`. Send objects directly via `postMessage`, not JSON-encoded strings. Current portal support is v1 only.

### POC to portal

```ts
type PocMessage =
  | { type: 'poc:ready'; v: 1 }
  | { type: 'poc:refresh'; v: 1 }
  | { type: 'poc:resize'; v: 1; height: number }
  | { type: 'poc:navigate'; v: 1; path: string };
```

### Portal to POC

```ts
type PortalMessage =
  | {
      type: 'portal:session';
      v: 1;
      token: string;
      expiresAt: string;
      user: { id: string; displayName: string };
      theme: 'light' | 'dark';
    }
  | { type: 'portal:theme'; v: 1; theme: 'light' | 'dark' }
  | { type: 'portal:navigate'; v: 1; path: string }
  | {
      type: 'portal:session-ended';
      v: 1;
      reason: 'logout' | 'trial-expired' | 'error';
    };
```

`expiresAt` is an ISO-8601 UTC string such as `2026-09-08T10:30:00.000Z`, not epoch milliseconds. The JWT's verified `exp` remains the backend authority. Bridge themes are lowercase, unlike the JWT theme claim. `user` is a display convenience, not proof of identity for a backend.

### Receiving and sending safely

- Register the message listener before announcing readiness.
- Send using `window.parent.postMessage(message, configuredPortalOrigin)` with an explicit origin, never `*`.
- Before reading payload fields, require `event.origin === configuredPortalOrigin` and `event.source === window.parent` in the POC adapter. The portal independently checks the origin and the exact iframe window.
- Validate object shape, message type, protocol version, and field values. Require a nonempty session token, parseable future session expiry, valid user fields, and a recognized theme. Do not crash on unrelated messages.
- Ignore unknown types, unsupported versions, malformed messages, wrong origins, and wrong sources. Do not echo payloads or credentials into logs. Development diagnostics may describe failed checks without the token.
- Do not infer trust from `document.referrer`, `location.ancestorOrigins`, URL parameters, or whichever origin sends the first message.
- Treat session termination as terminal for the current bridge instance. Ignore delayed replacement sessions after termination; only a fresh mount/explicit new session lifecycle can restart it.

### Handshake and visible states

Send `{type:'poc:ready', v:1}` on startup. Retry with bounded backoff; the current reference client retries at 250, 500, 1000, 2000, and 4000 milliseconds, and times out after 15 seconds. Equivalent bounded behavior is acceptable.

Before a session, render only a public loading shell and make no protected requests. After receiving a session, store the token in memory, apply the theme, display the supplied user name where useful, then enable the business application.

Support explicit UI states:

| State | Required behavior |
|---|---|
| Waiting | Loading shell; no data/compute requests |
| Ready | Authenticated business UI |
| Opened directly, outside an iframe | Show “Open this POC from the self-service portal”; no production standalone bypass |
| Handshake timed out | Visible recoverable message; no infinite spinner or unauthorized fallback |
| Session ended: logout | Clear sensitive UI/state and ask the user to reopen through the portal |
| Session ended: trial-expired | Clear sensitive UI/state and show that trial access has ended |
| Session ended: error | Stop protected work and show a generic session error |

On termination, clear the token and user-specific caches, cancel refresh timers, reject/resolve pending waiters, stop polling/streams, and prevent in-flight responses from restoring private UI. Cancel requests where possible. An abort does not undo an operation the backend has already executed.

### Renewal and HTTP behavior

- Keep POC tokens in memory only: no cookies, `localStorage`, `sessionStorage`, IndexedDB, URLs, analytics, or logs.
- Schedule renewal before expiry; the reference bridge uses approximately 75% of the announced remaining lifetime with a minimum delay of five seconds. Never keep issuing requests with a token known to be expired.
- Send `poc:refresh`; the reply is another `portal:session`, not `portal:refresh` or a separate refresh-token response.
- Replace the token and expiry atomically, cancel the previous scheduled refresh, and retain the live iframe and current business UI state.
- Coalesce concurrent renewal requests into one pending operation with a bounded timeout (15 seconds is the reference default).
- On a protected request's `401`, request renewal and retry at most once with the new token. A second `401` must propagate; never loop indefinitely.
- Do not refresh automatically on `403`, quota errors, validation errors, or arbitrary third-party failures.
- Retry mutations only when authentication rejection is known to occur before business execution and the request body is replayable. Use appropriate idempotency for operations that need it; never replay a consumed upload stream blindly.
- If renewal fails, stop protected requests once the existing token expires and show a recoverable error. Do not treat a failed renewal as a new authenticated session.

Attach the token only to an explicit allowlist: the POC's own API paths and, when used, the platform's `/poc-files` surface. Resolve request URLs structurally using origin and path boundaries, not loose string prefixes. Do not forward a POC token to analytics, third-party APIs, arbitrary URLs, redirects to another host, or public configuration/assets. Fetch public runtime configuration before the authenticated client is initialized.

### Theme, sizing, and navigation

Apply both the initial session theme and later `portal:theme` messages without a reload. Use `light`/`dark` exactly; `sails:theme` with a `mode` property is not this contract. Respect the POC's accessibility and layout requirements while making all main surfaces readable in both modes.

`poc:resize` and navigation messages are optional. The current portal workspace uses a full-height iframe and does not apply resize messages or mirror POC navigation into its URL. Do not make core behavior depend on these features. If supporting incoming navigation, accept only valid internal routes; never allow arbitrary external navigation or execution.

## 6. Install and use the shared bridge package

The shared package is **`@yateesha-pappala/poc-bridge`**, hosted on **GitHub Packages**. Use this exact scope in dependencies, registry configuration, and imports. The former `@sails/poc-bridge` name is obsolete. The portal repository maintains the implementation under `projects/poc-bridge` and still compiles it through source aliases; POC repositories consume the built package.

| Import | Purpose |
|---|---|
| `@yateesha-pappala/poc-bridge` | Shared types and guards |
| `@yateesha-pappala/poc-bridge/poc` | Angular POC client, provider, interceptor, development harness |
| `@yateesha-pappala/poc-bridge/host` | Portal host; not the POC client |

The inspected package manifest declares **version `0.1.0`**, with peer dependencies `@angular/common: ^21.0.0`, `@angular/core: ^21.0.0`, and `rxjs: ^7.8.0`. The repository README reports publication to GitHub Packages; the package metadata, ng-packagr build configuration, and publishing workflow are present. Registry access and an actual consumer installation were not verified while updating this guide. Package version `0.1.0` and wire protocol `v: 1` are separate version numbers.

### Registry configuration and installation

Create this `.npmrc` in the POC repository:

```ini
@yateesha-pappala:registry=https://npm.pkg.github.com
//npm.pkg.github.com/:_authToken=${NODE_AUTH_TOKEN}
```

Commit only this environment-variable placeholder, never a real token. Supply `NODE_AUTH_TOKEN` through the developer environment or the build system's secret mechanism. The package README specifies a PAT with `read:packages` for local consumption. In GitHub Actions, a suitably authorized `GITHUB_TOKEN` may be provided as `NODE_AUTH_TOKEN`; ensure the consuming repository has access to this package. Do not assume a token from any repository can read it.

Install the inspected version explicitly and commit the resulting dependency/lockfile changes:

```bash
npm install --save-exact @yateesha-pappala/poc-bridge@0.1.0
```

If this fails because of registry authorization or version availability, resolve access with the platform contact. Do not silently switch to npmjs.com, install the old scope, or implement another bridge merely to bypass a package access problem.

**Container builds also need package-read access.** If a Dockerfile runs `npm ci`, an authenticated developer workstation or GitHub checkout alone does not supply credentials inside that build. Arrange an explicit build-time secret mechanism with the platform team. Never put the token in `poc.yaml`, Dockerfile literals, committed `.npmrc` credentials, browser runtime configuration, or persistent image layers. The current manifest does not itself declare npm build secrets. Mark container verification blocked if the required build-time access is not supplied.

### POC imports and framework compatibility

For a compatible Angular POC, use the shared client rather than reimplementing the handshake:

```ts
import {
  PocBridge,
  providePocBridge,
} from '@yateesha-pappala/poc-bridge/poc';
```

Register `providePocBridge({ portalOrigin: runtimeConfig.portalOrigin })`, where `runtimeConfig` is the validated configuration loaded before bootstrap. Start the injected `PocBridge` once using `waitForSession()`, render its state, and gate protected screens on `status() === 'ready'`. Use a destination-scoped HTTP adapter as described below. The `/poc` entry point also exports `pocBridgeInterceptor`, `startDevHarness`, the configuration/status types, and the shared protocol. The `/host` entry point is for the portal, not for bootstrapping a POC.

The supplied POC client is Angular-specific. Do not force-install incompatible peers or migrate a React/Vue/other POC merely to import it. For an incompatible framework, agree on a compatible adapter/source distribution with the platform contact; section 5 remains the complete wire specification. Keep any required framework-specific adapter isolated and tested rather than changing the portal protocol.

For platform maintainers, `npm run build:poc-bridge` builds `dist/poc-bridge`; `npm run pack:poc-bridge` builds a tarball for consumer testing. `.github/workflows/publish-poc-bridge.yml` publishes version-matched `poc-bridge-v*` tags, while manual dispatch performs a build/pack dry run. A platform-supplied tarball is an explicit local verification option; record its version/source revision and do not leave a developer-specific tarball path in the final deployment setup.

When using the current Angular source, account for these implementation details:

- `waitForSession()` also resolves on failure/terminal states. Check `status() === 'ready'` before loading protected data; resolution alone is not authentication.
- The reference interceptor attaches credentials to every outgoing request. Wrap or replace it with a destination-scoped interceptor before using an HTTP client that also accesses other hosts.
- The POC-side reference listener checks origin but does not currently check parent-window source. Add that check in a local integration where needed to meet this guide's defensive requirement.
- Application code must clear sensitive UI/state on termination. Verify that the chosen bridge implementation cancels old timers and ignores delayed sessions; the current reference implementation is not a substitute for those acceptance tests.

If using an Angular application initializer, ensure a visible static loading shell and render failure states after initialization settles. Verify the installed package through the POC's own production build and tests; the portal's source aliases do not test external package resolution.

## 7. Public shell, HTTP headers, and API transport

Serve HTML, JavaScript, CSS, the minimal runtime configuration, and a non-sensitive health endpoint without a bearer token. Browser document/subresource loads cannot use the bridge token before the shell and bridge exist. All data, model execution, user configuration, downloads, and other protected operations MUST be behind verified authentication.

On iframe HTML responses, set this HTTP response header using the validated runtime origin:

```http
Content-Security-Policy: frame-ancestors https://portal.example.com
```

Merge the directive into the POC's existing CSP rather than discarding unrelated protections. `frame-ancestors` must be an HTTP header, not only a meta tag. Remove/adjust `X-Frame-Options: DENY` or `SAMEORIGIN` on the framed shell because they conflict with cross-origin portal embedding. Check proxy and framework defaults as well as application code. Do not reflect an arbitrary request `Origin` into framing policy.

Prefer same-origin browser calls through an ingress `/api` proxy. If the POC intentionally uses a separate browser-facing backend origin, configure CORS for the actual **POC frontend origin**, including authorization-header preflight, rather than assuming requests originate from the portal origin. Do not introduce cross-site login cookies.

Do not put tokens into download URLs. Fetch authenticated bytes and create/revoke a browser Blob URL when a download is needed. Native EventSource and browser WebSocket APIs need transport-specific authentication handling; if the POC uses them, implement a compatible authenticated fetch/stream design or raise a scoped design question. Do not silently fall back to query-string bearer tokens.

Protected responses and user data must not enter shared/public caches. Keep logs useful without recording Authorization headers, session messages, model secrets, or raw personal file contents.

## 8. User data and optional platform files

For POC-owned persistent records, use verified `sub` as the owner. Include POC scope where a backend or datastore serves multiple POCs. Apply ownership in database queries and on read, update, delete, export, job-status, and stream endpoints. A caller-supplied record ID must never bypass ownership. Never use `displayName` as an identity key or share a global “current user” variable across requests.

In-memory data disappears across process restarts and is not shared across instances. If persistence is required, obtain the actual datastore configuration; the platform does not currently provision a POC database merely because a manifest requests one. Do not assume access to the platform API's own database.

If the POC needs the platform-managed document store, the following routes accept the **POC token**, not a portal access token. They are rooted at `PLATFORM_API_URL`, without an assumed `/api/v1` prefix.

| Operation | Request | Successful response |
|---|---|---|
| Upload | `POST /poc-files`, multipart with exactly the field `file` | `201`, file metadata |
| List | `GET /poc-files` | `200`, array of file metadata |
| Read bytes | `GET /poc-files/{fileId}/content` | `200`, raw bytes |
| Delete | `DELETE /poc-files/{fileId}` | `204`, no body |

```json
{
  "id": 42,
  "originalFilename": "example.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 12345,
  "uploadedAt": "2026-09-08T10:00:00Z"
}
```

There are no user-ID or POC-ID parameters: platform authorization derives both from the token. Do not add them. For uploads using browser FormData, let the browser generate the multipart Content-Type boundary. Handle `400` validation/type failures, `401` authentication failures, `403` trial/access denial, `404` unavailable or non-owned files, `409` quota exceeded, and `413` file too large. Limits and allowed types are platform configuration; do not promise arbitrary file formats or sizes.

File endpoints can be called by the frontend or by the POC backend forwarding the incoming user's token to this explicitly trusted platform destination. Do not obtain/store a platform admin credential. A long-running background task cannot independently refresh this token; establish an explicit design if it must access platform files after the user's token expires.

A shared uploader UI package is not currently available in the inspected bridge. Implement the POC's upload UI if needed. `platform.files.enabled` does not automatically create that UI or credentials.

## 9. Container and manifest contract

The current platform API builds containers from one POC repository and deploys them as one Cloud Run service with exactly one ingress container. Sidecars share the instance network and must use distinct listening ports. Docker Compose may be used for local testing but is not the deployment input.

Create `poc.yaml` (or `poc.yml`; both are read, `poc.yaml` first) at the repository root. Use `containers`, not an older proposed `components` schema. Paths below are examples; change them to real paths in the POC repository.

### Single container serving frontend and backend

```yaml
containers:
  - name: app
    role: ingress
    dockerfile: Dockerfile
    context: .
    health: /health
```

### Frontend ingress with a backend sidecar

```yaml
containers:
  - name: frontend
    role: ingress
    dockerfile: frontend/Dockerfile
    context: .
    health: /health
  - name: backend
    role: sidecar
    dockerfile: backend/Dockerfile
    context: .
    port: 8081
    health: /health
```

Both use a root build context for clarity. Ensure every Dockerfile's COPY paths work with the declared context and build it that way locally. The frontend must actually proxy to the backend in the second example; declaring a sidecar does not create proxy routes.

### Keeping the POC's own environment variable names

The two examples above make a POC read `PORT` and `SVC_BACKEND_URL`, names the platform chose. An application that already worked does not have to adopt them. A `${...}` reference inside an `env:` value binds a platform value to whatever name the code already reads:

```yaml
containers:
  - name: frontend
    role: ingress
    dockerfile: frontend/Dockerfile
    context: .
    port: 3000
    health: /health
    env:
      BACKEND_API_URL: ${services.backend.url}
      SERVER_PORT: ${self.port}

  - name: backend
    role: sidecar
    dockerfile: backend/Dockerfile
    context: .
    port: 8081
    health: /health
    env:
      SERVER_PORT: ${self.port}
      ALLOWED_ORIGIN: ${portal.origin}
      EXPECTED_AUDIENCE: poc:${poc.slug}
```

Available references: `${self.port}`, `${self.name}`, `${services.<name>.url}`, `${services.<name>.host}`, `${services.<name>.port}`, `${poc.slug}`, `${platform.apiUrl}`, `${portal.origin}`.

A reference naming one of those roots must resolve or the manifest is rejected before anything is built. Any other `${...}`, such as `${HOME}`, is left as ordinary text. Write `$${` for a literal `${` that would otherwise start a known root.

These are aliases, not replacements. `PORT`, `SVC_<NAME>_URL`, `POC_SLUG`, `PLATFORM_API_URL` and `PORTAL_ORIGIN` are still injected exactly as section 3 describes, so a POC already written against them needs no change.

**A copy-paste starting point with every key commented is `docs/poc.yaml.template`, beside `docs/poc.schema.json`.** Referencing the schema from the top of your own `poc.yaml` gives validation and autocomplete in any editor with the YAML extension. Both files are checked against this platform's own parser and validator by an automated test, so they cannot drift from what a deploy actually accepts.

Rules and supported fields:

- Exactly one `role: ingress`; every other container uses `role: sidecar`.
- Names must be unique, lowercase alphanumeric with hyphens, at most 40 characters. Default platform maximum is eight containers.
- Ingress `port` is optional. The platform default is 8080 and a declared ingress port takes precedence, on both the single-container and the multi-container path. The process must bind the port it will actually be given: the supplied `PORT`, or the declared port surfaced under the POC's own name with `${self.port}`.
- Every sidecar declares its listening `port`. No two containers may use the same port, ingress included, and a manifest that reuses one is rejected before anything is built — an ingress that declares none is checked at the platform default of 8080. The platform supplies matching `PORT` and `SVC_<NAME>_URL` values.
- `dockerfile` defaults to `Dockerfile`; `context` defaults to `.`. Declare them explicitly when the repository has multiple builds.
- `health` is an optional per-container HTTP path used for a startup probe. Implement it if declared; prefer a small unauthenticated `200` response with no secrets. Do not make an absent user session fail health checks.
- The ingress is configured to depend on sidecars that declare health probes. It should still handle backend unavailability gracefully.
- `env` is a map of non-secret literals. Do not put API keys, passwords, or tokens in it.
- Do not set reserved names `PORT`, `POC_SLUG`, `PLATFORM_API_URL`, `PORTAL_ORIGIN`, or names with `SAILS_` or `SVC_` prefixes in `env`; the platform owns them.
- Optional top-level `resources: {cpu: "1", memory: "512Mi"}` and `scaling: {min: 0, max: 2}` are supported. Current resource flags apply to the single container or the ingress in a multi-container service; they do not configure every sidecar. Choose actual capacity with the platform contact; these examples are not mandatory limits.
- Optional `platform: {database: {enabled: true}, files: {enabled: true}}` is parsed but currently does not provision resources or inject database credentials. Do not rely on it to configure persistence.
- A container-level `repo` pointing to another repository is rejected. All containers build from the primary POC repository.
- Unknown top-level/container keys are currently warned about and ignored. Do not interpret successful parsing as proof that an invented key has an effect.

Use production build artifacts, reproducible dependency installation, and a foreground runtime process. Include the correct runtime entrypoint and `.dockerignore`; omit local credentials and build caches. Container startup must translate environment settings into runtime configuration/proxy configuration as needed. Do not bake environment-specific portal/API hosts into the frontend bundle.

Secrets provisioning for arbitrary POC containers is not guaranteed by the current manifest implementation. Obtain a supported delivery mechanism from the platform contact. Do not “solve” missing credentials by committing them or inventing a `secrets:` manifest key.

## 10. Platform launch endpoint, for context and local testing

The **portal** calls:

```http
POST /pocs/{slug}/launch
Authorization: Bearer <portal-access-token>
```

There is no request body. Example response shape:

```json
{
  "token": "<signed-poc-jwt>",
  "expiresIn": 900,
  "launchUrl": "https://poc-service.example.com",
  "pocId": 42,
  "slug": "example-poc"
}
```

The POC itself MUST NOT need the portal access token or call this endpoint for renewal. It sends `poc:refresh` instead. The public key endpoint is `GET /.well-known/jwks.json`, with no token required. These are explicit endpoints; do not assume OIDC discovery exists or treat the issuer string as a discovery URL.

For local integration, use a real test user's portal session and a platform-minted POC token for the registered slug. Any temporary development harness must be excluded from production, use an explicitly configured local parent origin, and still exercise backend signature/issuer/audience validation. Keep test credentials out of source control and persistent browser storage. Local expiry tests can use isolated test keys/JWKS fixtures in the test environment; never introduce a production “accept test token” path.

If the deployed catalog URL does not point at the local POC, use a local parent harness that reproduces v1 messages, or ask the platform contact to configure a development catalog entry. Do not expect a production portal iframe to discover the local application automatically.

## 11. Recommended implementation sequence

1. Inventory protected routes and current authentication; write down the user-owned resources and external dependencies.
2. Add validated backend/browser runtime configuration and a public shell/health boundary.
3. Implement backend JWT/JWKS middleware and ownership checks; verify negative authentication cases before enabling real data operations.
4. Implement the v1 bridge adapter, visible session states, in-memory storage, renewal, and live theme updates.
5. Add a destination-scoped authenticated HTTP client and bounded retry handling; connect business screens only in ready state.
6. Adapt file/persistence features if used; keep business secrets server-side.
7. Implement ingress proxying where needed, CSP, and production container startup.
8. Add the manifest and build each container using its declared paths/context.
9. Run the acceptance matrix, then exercise the real portal-to-POC flow in the configured environment when access is available.
10. Report completed changes, tests, and external blockers accurately. A passing mocked handshake does not establish a successful deployed integration.

## 12. Troubleshooting

| Symptom | Check |
|---|---|
| Iframe “refused to connect” | Cloud Run shell reachability; CSP; conflicting X-Frame-Options; ingress reaching the correct container |
| Shell loads but waits forever | Runtime origin equals actual portal origin; listener registered before ready; numeric `v: 1`; exact message names; bounded timeout UI |
| Theme never changes | `portal:theme` and `theme`, lowercase values; not `sails:theme`/`mode` |
| Every API call returns 401 | Bearer forwarded by proxy; issuer is confirmed literal, not guessed URL; slug matches audience; JWKS is reachable and returns keys |
| JWKS returns HTML or a POC page | Platform base URL mistakenly points to the POC, ingress, or deployed localhost |
| POC A token works against POC B | Missing/wrong backend audience validation; expected audience must come from B's configuration |
| Data flashes before login/after logout | Data components not gated on ready state; stale in-flight responses/caches not cleared |
| Repeated refresh calls | Concurrent renewals not coalesced; old timers not canceled; interceptor retries without a bound |
| Backend requests fail only in browser | A sidecar localhost URL leaked to browser config; proxy path mismatch; incorrect CORS/preflight |
| 502 during startup | Backend port/bind mismatch, missing health probe, incorrect sidecar URL, proxy startup behavior |
| Build succeeds but runtime config is old | Frontend values were compiled in; runtime JSON cached; startup script not executed |
| Manifest database flag has no effect | Provisioning is not implemented by that flag; obtain actual datastore configuration |

## 13. Acceptance checklist

Mark each item PASS, FAIL, NOT RUN, or NOT APPLICABLE, with evidence. Use fixture signing keys only in isolated tests. Do not claim real environment verification from unit tests alone.

### Authentication and isolation

- [ ] Missing token cannot read data, mutate state, upload/download, start a job, or invoke a paid/model API.
- [ ] Valid intended-POC token authenticates as verified `sub`.
- [ ] Invalid signature, wrong key, unknown unresolved `kid`, unsigned token, and disallowed algorithm are rejected.
- [ ] Wrong issuer, missing/wrong audience, another POC's token, and a portal access token are rejected.
- [ ] Expired token, malformed required claims, and future `nbf` when present are rejected.
- [ ] Expired trial denies protected work even if JWT `exp` is later; absent trial claim follows the documented behavior.
- [ ] User A cannot read/update/delete/export or inspect jobs/files belonging to user B by changing an ID.
- [ ] JWKS cache/unknown-key refresh is bounded; verification outages fail closed; rollover fixtures succeed with a newly published valid key.
- [ ] Public health, static shell, and safe runtime config work without a bearer token and reveal no private data.

### Browser and session lifecycle

- [ ] Embedded startup receives a v1 session and only then loads protected data.
- [ ] Wrong origin/source, malformed payloads, and unsupported versions are ignored without crashes or credential logs.
- [ ] Direct production opening shows the portal-launch message; missing session times out visibly.
- [ ] Proactive renewal replaces the token without reloading the iframe or losing current UI state.
- [ ] Concurrent 401s create one renewal; a request retries at most once; 403 does not cause a refresh loop.
- [ ] Renewal timeout and expired-session behavior stop unauthorized work and present recovery UI.
- [ ] Session-ended clears sensitive state, stops timers/streams, and ignores late sessions/responses.
- [ ] Initial and live light/dark themes work.
- [ ] Token never enters persistent browser storage, cookies, URLs, logs, or third-party requests.
- [ ] Optional files: upload/list/download/delete work with the POC token; quota/type/size failures are understandable.
- [ ] Any streaming or non-replayable requests have explicitly tested authentication/retry behavior.

### Packaging and real integration

- [ ] Compatible Angular POCs install the pinned `@yateesha-pappala/poc-bridge` package and resolve `/poc` imports in their own production build; incompatible frameworks document the agreed adapter.
- [ ] Package-read authentication works in both local installation and the actual container build without exposing credentials.
- [ ] Every declared Dockerfile builds using its manifest context; startup uses runtime `PORT` and binds correctly.
- [ ] Exactly one ingress; sidecar ports are distinct; proxy preserves auth/body/status and uses runtime service URLs.
- [ ] Declared health endpoints work; SPA routes and assets load through the ingress.
- [ ] Runtime origin/API configuration can change without rebuilding the frontend.
- [ ] CSP permits the intended portal and prevents an unrelated origin from framing the POC; no conflicting shell X-Frame-Options.
- [ ] No real secrets in manifest, browser assets, runtime JSON, Docker layers, or committed examples.
- [ ] Actual portal launch, backend data call, token renewal, theme toggle, and logout/trial termination have been exercised in the target environment, or are explicitly marked NOT RUN with the prerequisite.

## 14. Known platform limits and source provenance

The API contract in this guide was checked against `self-service-api` revision `764e640`. Package naming, metadata, consumption instructions, and bridge implementation were rechecked against `self-service-portal` revision `898b749`, updating the earlier portal snapshot `0a3a402`. Package installation against the live registry was not exercised. The guide also reviewed the separate `poc-deploy-pipeline` repository. The implemented API now uses its in-process deployment implementation; migration V20 removes `deployment_jobs`. The separate pipeline's README/queue and gateway assumptions are not the runtime contract for this snapshot. Older architecture notes also contain historical “not built yet” statements that predate the implementation.

Do not assume any of these capabilities exist without separate confirmation: OIDC discovery, centrally enforced authentication in a POC reverse proxy, immediate JWT revocation, automatic database provisioning, arbitrary manifest secrets injection, automatic npm credentials inside container builds, portal route mirroring, or a shared file-uploader component.

Maintainers updating this guide should compare the actual implementation at these repository-relative locations:

| Repository | Source |
|---|---|
| self-service-api | `src/main/java/com/sails/ai/selfserviceapi/security/JwtService.java`, `JwtProperties.java`, `JwksController.java`, `TrialAuthorizationManager.java` |
| self-service-api | `src/main/java/com/sails/ai/selfserviceapi/poc/service/PocLaunchService.java` |
| self-service-api | `openapi/self-service-api.yaml`, `openapi/components/schemas/poc.yaml`, `openapi/components/schemas/file.yaml` |
| self-service-api | `src/main/java/com/sails/ai/selfserviceapi/deploypipeline/manifest/ManifestParser.java`, `ManifestValidator.java` |
| self-service-api | `src/main/java/com/sails/ai/selfserviceapi/deploypipeline/run/CloudRunDeployCommandBuilder.java`, `src/main/resources/application.yaml` |
| self-service-portal | `projects/poc-bridge/src/lib/protocol.ts`, `projects/poc-bridge/src/lib/poc/`, `projects/poc-bridge/src/lib/host/poc-frame-host.ts` |
| self-service-portal | `src/app/components/poc-workspace/poc-workspace.ts`, `projects/poc-bridge/README.md` |
| self-service-portal | `projects/poc-bridge/package.json`, `projects/poc-bridge/ng-package.json`, `.github/workflows/publish-poc-bridge.yml`, `tsconfig.json` |

These paths are provenance for maintainers, not additional reading required to implement this handoff. If a newer platform release changes a wire field, verification rule, or deployment guarantee, update this document and its acceptance checks together before sending it to another POC team.

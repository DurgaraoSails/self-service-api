# POC Bridge Contract

## Status

Draft — designed, not implemented. Defines the contract between the portal and an embedded POC; the architectural reasoning behind it lives in `docs/specs/poc-hosting-architecture.md`.

*Scope note: unlike the other specs here, this one describes a package (`@sails/poc-bridge`) that does not live in `self-service-api`. It is kept alongside the rest of the POC platform design because that is where the decisions it implements are recorded.*

## Overview / Purpose

A POC runs in an iframe on its own origin, with no cookie and no shared JavaScript context with the portal. Everything crossing that boundary — identity, theme, sizing, navigation, session lifecycle — travels over `postMessage`. This document is the contract for that channel, and the specification for the library that implements it.

It exists because the boundary is the only thing a POC author has to integrate with, and because every part of it is easy to reimplement subtly wrong. The audience-check in backend verification is one line that is invisible when omitted; the `postMessage` origin checks are two lines that are invisible when omitted. Those belong in a shared, versioned package, not in twenty teams' judgement.

The library is the reason the iframe boundary is acceptable rather than merely tolerable. An unassisted iframe is a mismatched box with its own scrollbar, no theme, no identity, and no deep-linking; each of those is solved here.

## Requirements

- A POC obtains the signed-in user's identity without ever seeing the portal's own tokens.
- A POC's data requests are authenticated, and the token refreshes without the user noticing.
- A POC matches the portal's light/dark theme, and follows changes to it live.
- A POC sizes its own frame rather than living inside a fixed box with nested scrollbars.
- A POC's internal navigation can be reflected in the portal's URL, so a deep link works.
- A POC opened outside the portal fails clearly rather than hanging.
- A POC author can develop and test without running the portal.
- POCs deploy independently and will lag the portal, so the contract must tolerate version skew.

## The Message Channel

### Envelope

Every message is an object carrying a namespaced `type` and a protocol version:

```ts
{ type: 'poc:ready', v: 1, ... }      // POC  → portal
{ type: 'portal:session', v: 1, ... } // portal → POC
```

**Namespacing is not decoration.** The `message` event is a shared bus: analytics scripts, embedded widgets, browser extensions, and framework devtools all post to the same window. A listener that assumes every message is its own will crash on the first stray one. Both sides therefore ignore, silently and without logging, any message that fails origin, shape, or type checks.

**Versioning exists because POCs lag.** A POC is deployed on its own schedule and may run a bridge release older than the portal. The portal must accept the current protocol version and at least the one before it, and must never assume a POC understands a message type newer than the version that POC announced in `poc:ready`. This is the version-skew problem that ruled out Module Federation in `poc-hosting-architecture.md`, reduced to a message contract — small enough to support across releases, which is precisely why the boundary was drawn here.

### POC → portal

| Type | Payload | Meaning |
|---|---|---|
| `poc:ready` | `{ v }` | Bridge loaded; requesting a session. Sent on load, retried with backoff. |
| `poc:refresh` | `{ v }` | Token near expiry or a `401` was seen; requesting a fresh one. |
| `poc:resize` | `{ v, height }` | Content height changed; portal resizes the frame. |
| `poc:navigate` | `{ v, path }` | Internal route changed; portal may mirror it into its own URL. |

### Portal → POC

| Type | Payload | Meaning |
|---|---|---|
| `portal:session` | `{ v, token, expiresAt, user: { id, displayName }, theme }` | Answer to `poc:ready` or `poc:refresh`. |
| `portal:theme` | `{ v, theme }` | User toggled the theme; apply it live. |
| `portal:navigate` | `{ v, path }` | Deep link — restore this internal route. |
| `portal:session-ended` | `{ v, reason }` | Terminal. `reason` is `logout`, `trial-expired`, or `error`. |

`portal:session` carries `user` and `theme` as a convenience so the frontend need not decode a JWT. The token's claims remain authoritative; the payload is not.

## Handshake

```
POC shell loads (public, unauthenticated, renders a skeleton)
        │
        ├── poc:ready ──────────────────────────► portal
        │      targetOrigin = PORTAL_ORIGIN            │
        │      retried with backoff                    │
        │                                              │ verify e.origin === pocOrigin
        │                                              │ verify e.source === iframe.contentWindow
        │                                              │
        ◄───────────────────────── portal:session ─────┤
        │                          targetOrigin = pocOrigin
        │
   token held in memory · theme applied · app boots
```

### Origin handling

**`PORTAL_ORIGIN` is required runtime configuration, injected by the deployment pipeline.** It is never derived from `document.referrer` or `location.ancestorOrigins`, both of which mean trusting the environment the POC finds itself in.

It is deliberately not compiled into the library, and equally deliberately not read from a committed `environment.prod.ts` in each POC. A published package that bakes environment config needs one build per environment, which is what versioned packages exist to avoid; and per-POC environment files make production correctness a matter of twenty teams' discipline, while making a change to the portal's origin a twenty-repo edit. Instead `poc-deploy-pipeline` sets it as a Cloud Run environment variable at deploy time, and the POC's container serves it into the page — a `/config.json` fetched before bootstrap, or a value written into `index.html` at serve time. The bridge accepts it through `providePocBridge({ portalOrigin })` with **no default**, so a missing or malformed value throws at bootstrap rather than degrading quietly.

The same environment variable supplies the `frame-ancestors` origin in the POC server's `Content-Security-Policy` header, so the JavaScript origin check and the browser-enforced embedding restriction cannot disagree.

A correct value does defensive work for free: a `postMessage` whose `targetOrigin` does not match the real parent is **silently discarded by the browser**. A POC embedded in a hostile page therefore never emits its `ready`, never receives a token, and produces no error worth probing. The failure is quiet and total.

This is defence in depth rather than the primary control, and the distinction is worth keeping straight. A hostile parent that somehow completed a handshake could only inject a token, which then fails signature, `iss`, and `aud` verification at the POC's backend. The load-bearing controls are that verification and `frame-ancestors`; a misconfigured origin is a real weakness, not a breach.

On the portal side, `pocOrigin` is `new URL(launchUrl).origin` — the origin it just launched. Both the `origin` and `source` checks are required: origin alone does not distinguish the POC's own document from a nested frame inside it.

**`targetOrigin` is never `*`, in either direction.** A wildcard hands a live token to any page that manages to embed the POC.

### Ordering

The portal attaches its `message` listener **before** setting the iframe's `src`. A POC that loads quickly can otherwise emit `poc:ready` before a listener exists, and the handshake deadlocks silently with no error on either side. The bridge's retry-with-backoff on `poc:ready` is the second line of defence against exactly this.

## Frontend Integration

### Blocking bootstrap

A POC must not issue a data request before it holds a token. The natural Angular reflex — fetching in `ngOnInit` or a plain `APP_INITIALIZER` — does exactly that. The bridge prevents it by being the initializer:

```ts
provideAppInitializer(() => inject(PocBridge).waitForSession())
```

Angular renders nothing until the session arrives, so no component can fire early.

### Token handling

The token lives in a **module-scoped variable**. Not `localStorage`, not `sessionStorage`.

`sessionStorage` would technically work — it is partitioned per origin per top-level site and survives reloads within a tab — but the token lives minutes and costs one round trip to replace, so persisting it widens the XSS window for no benefit. A reload simply re-runs the handshake.

### HTTP interception and refresh

The bridge ships an interceptor that attaches the token and handles `401 → refresh → retry once`, mirroring `core/http/http-interceptor.ts` in the portal. Refresh is also scheduled proactively at roughly 70–80% of the token's lifetime, so ordinary requests rarely see a `401` at all.

**Concurrent refreshes must collapse into one.** If a screen fires five requests and all five receive a `401`, five independent refreshes would stampede `/pocs/{slug}/launch` and — worse — four retries would carry a token that the fifth refresh has already superseded. A single in-flight promise that all callers await is the required behaviour, not an optimisation.

### Theme, sizing, navigation

Theme is applied as an attribute on the POC's root element which the design-token CSS keys off, so `portal:theme` restyles live with no reload.

Sizing is a debounced `ResizeObserver` on the document element emitting `poc:resize`. Without it the POC lives in a fixed frame with its own scrollbar inside the portal's — the single most recognisable symptom of a badly embedded iframe.

Navigation is opt-in. A POC that emits `poc:navigate` gets deep links; one that does not still works.

### File upload

The uploader is a bridge component, not something each POC builds. It renders inside the POC's own layout, calls the platform API directly, and is identical everywhere, so progress, error handling, and quota messaging stay consistent without any POC implementing them. See `docs/specs/file-management.md`.

### Local development

The bridge ships a **dev harness** that fakes the portal side of the handshake, so a POC author can run their app standalone against a locally-issued token. Without it, the only way to see a POC render is to run the portal and the API together, which makes the boundary a tax on every POC team every day rather than a one-time integration.

## Backend Verification

What a POC's backend does with the token, in order:

1. Read `kid` from the token header.
2. Resolve it against the cached JWKS from `GET /.well-known/jwks.json`; on a miss, refetch — **rate-limited**, or a forged `kid` becomes a fetch amplifier against the API.
3. Verify the RS256 signature.
4. Verify `iss` matches the API's issuer.
5. Verify `exp`, allowing roughly 60 seconds of clock skew.
6. Verify `aud` equals `poc:` + the POC's own slug.
7. Take `sub` as the user id.

### Step 6 is the one that carries the design

The audience claim is what stops one POC replaying its token against another. A POC that skips it will treat the bearer as that user *inside itself*, and hand over its own data — which the platform API cannot prevent, because a Tier 2 POC's data lives in its own schema and never passes through `self-service-api`.

**The expected slug must come from the POC's own configuration** — `poc.yaml`, an environment variable — and never from the token. Reading the expected audience out of the object being validated is a check that looks like a check and validates nothing.

### Per-stack

| Stack | Mechanism |
|---|---|
| Java / Spring | `NimbusJwtDecoder.withJwkSetUri(...)` plus `JwtValidators.createDefaultWithIssuer` and an audience validator |
| Python | `PyJWT`'s `PyJWKClient` with `jwt.decode(..., audience=...)` |
| Node | `jose` — `createRemoteJWKSet` and `jwtVerify`, which take `issuer` and `audience` directly |
| Go | `lestrrat-go/jwx` with `jwk.NewCachedSet` |

## Failure States

| Situation | Behaviour |
|---|---|
| Opened directly, not framed (`window.parent === window`) | Render "this demo must be launched from the portal" — never an indefinite skeleton |
| Handshake times out after retries | Explicit error state |
| Embedded by a hostile page | `poc:ready` is discarded by the browser; nothing happens, by design |
| Token expires mid-session | `401` → refresh → retry, invisible to the user |
| Trial expires mid-session | `/launch` returns `403`; portal shows its trial modal and sends `portal:session-ended` |
| Portal tab closed | No refresh arrives; the POC goes inert when its token expires |
| POC cold start | Portal holds a skeleton over the frame until `poc:ready` |

## Security Requirements

Non-negotiable for any POC, and enforced by the template's conformance check where it can be:

- `targetOrigin` is always an explicit origin, never `*`.
- Every received message is checked for `origin`, and on the portal side `source`, before its contents are read.
- Unknown or malformed messages are ignored silently.
- The token is held in memory only.
- `aud` is verified against configuration, not against the token.
- Identity comes from `sub` alone — never from a header, query parameter, or request body. A POC that accepts a user id from the request hands every user's data to anyone who can type one.
- **No secrets in the POC's JavaScript bundle.** The shell is served publicly and unauthenticated by design, so anything in it is world-readable. Model provider keys, service credentials, and internal URLs live server-side without exception.
- POC responses set `Content-Security-Policy: frame-ancestors` restricted to the portal origin. This is a browser control — it prevents a rogue site embedding the POC, and does nothing against a non-browser client presenting a stolen token, which is what token verification covers.

## Ownership and Change Control

**The library lives in `self-service-portal` as an Angular workspace library (`projects/poc-bridge`), not in a repository of its own.**
The protocol has two implementations that must never drift: the bridge is one half, the portal's iframe host is the other. In separate repositories a protocol change becomes two pull requests that can land out of order, and disagreement between them surfaces as a runtime failure discovered by a POC team. In one repository the message types are declared once and imported by both halves, so drift is a compile error; a protocol change is a single atomic change; and the portal's own tests can exercise the entire handshake, host against bridge, without a POC involved.

`@sails/design-tokens` lives there too. The tokens are the portal's design language extracted for reuse, and the package being framework-agnostic is a property of its contents rather than a reason to house it elsewhere. Both are published to GitHub Packages from that repository's CI (see `poc-hosting-architecture.md` for why GitHub Packages).

The cost is that a POC author debugging the bridge clones the portal repository. That is real but rare, and POC teams are not the intended authors of protocol changes.

**This document and the library are reviewed as one artifact.**
`CODEOWNERS` covers `projects/poc-bridge/` and this spec together, because a contract change that lands without the document is how the document stops being true. The library is a shared dependency of every POC, so a breaking change to it breaks all of them simultaneously.

Additive changes — a new message type, a new optional field — take normal review. Breaking changes require both versions to be supported for at least one release, which the version negotiation above exists to make possible.

**The deprecation window is decided from data, not from argument.**
`poc:ready` carries `v`, so the portal can record which protocol versions are actually live across the deployed fleet. "Can we drop v1?" is therefore a query rather than a guess, and the admin fleet view is the natural place to surface it.

## Open Questions / Future Work

- **How many protocol versions does the portal support?** "Current and previous" is stated above as a starting rule. The real window should be set once `poc:ready` version telemetry exists to inform it, along with what the portal does when it meets a bridge older than the window — refuse to hand over a session, or hand one over and degrade.
- **Is the uploader one component or a headless primitive plus a default UI?** A single component is faster to ship and enforces consistency; a headless core lets a POC style upload into an unusual flow. The second is more work and can follow.
- **Deep-link semantics.** `poc:navigate` and `portal:navigate` assume the portal mirrors an opaque POC path into its own URL. Whether the portal validates that path, and what it does with a path a POC no longer recognises, is undefined.
- **Does the pipeline verify that `PORTAL_ORIGIN` was actually injected?** The conformance check can assert the variable is set on the Cloud Run service at deploy time, which would catch the one failure mode this design still has — a deploy path that forgets it, leaving the POC to throw at bootstrap in production rather than in CI.

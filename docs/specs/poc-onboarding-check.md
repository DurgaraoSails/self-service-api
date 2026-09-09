# POC Onboarding Readiness Check

## Status

In Progress

## Overview / Purpose

Today a team that wants its POC hosted in the portal asks the platform team, and every failed
onboarding comes back to that team as a support question. The goal is that a team lead can go from
"runs on my laptop" to "launchable in the portal" without asking anyone.

That goal has two halves, split across two repositories:

- **The guide** — an in-portal page telling a POC developer exactly what to do, assuming no Docker
  knowledge. Specified in `self-service-portal`, at `docs/specs/poc-onboarding-guide-page.md`.
- **The checker** — this document. `POST /poc-onboarding/check` takes a GitHub URL and answers
  "would this repository deploy, and if not, what exactly is wrong with it?"

The checker is the half that actually removes the dependency. A guide alone is prose, and prose
drifts from the validator it describes; the checker runs the platform's own resolution path, so its
answer cannot disagree with what a real deploy enforces.

This is phase 1 of onboarding: **no authentication integration, no poc-bridge, no file or database
management**. A team should be able to get a repo deployed and launchable, and nothing more.

### What this document does not repeat

The manifest contract itself is already specified and is not restated here:

| For | See |
|-----|-----|
| `poc.yaml` shape, validation rules, multi-container deploy | `docs/specs/poc-manifest-deployment.md` |
| Injected env vars (`PLATFORM_API_URL`, `POC_SLUG`, `PORTAL_ORIGIN`, `PORT`, `SVC_<NAME>_URL`) | `docs/specs/poc-container-environment.md` |
| Tag creation, build, and deploy sequence | `docs/specs/poc-deployment-pipeline.md` |
| The full POC contract including JWT auth and the portal bridge (phase 2+) | `docs/poc-integration-guide.md` |
| Version allocation from git tags | `docs/specs/poc-tag-driven-deployment.md` (paused) |

## Requirements

1. Accept a GitHub URL for a repository that has **no POC record yet** — this runs before creation,
   not after.
2. Report **every** problem in one pass, never fail-fast. A team fixing one thing per round trip is
   the failure mode this replaces.
3. Reuse the real code path. `GitHubService` and `ManifestService` decide the answer; this feature
   adds no second implementation of any rule.
4. Be readable by **any signed-in user**, not admins only. A lead evaluating feasibility should not
   need admin access first — that would be a request to the platform team, which is the dependency
   being removed.
5. Never leak repository contents. Findings plus the manifest the team wrote themselves, nothing
   else.
6. Be honest about its own limits. The API can read a repository; it cannot run the team's image.

## Architecture Decisions

### An unready repository is a 200, not a 4xx

`ready: false` with a list of findings is the **expected** answer, not an error. A 400 would push
callers into reading error bodies for normal results, and would collide with the genuine 400 for a
malformed request. The endpoint returns 4xx only when the call itself is wrong.

### `checkPushAccess` returns; `requirePushAccess` throws

`GitHubService.requirePushAccess` threw on the first of three distinct problems (repo not found or
invisible, repo archived, token lacks push). Throwing is right for a deploy, which must stop — but
the checker needs to report rather than stop.

The check is therefore split, not duplicated:

```java
public RepoAccess checkPushAccess(GitHubRepoRef repo)   // OK | NOT_FOUND | ARCHIVED | NO_PUSH
public void requirePushAccess(GitHubRepoRef repo)       // calls the above, throws on anything but OK
```

The messages live on `RepoAccess.describe(repo)`, so the deploy and the checker say the same words.
A second implementation of "can we deploy this repo" is exactly how a checker starts telling teams
their repository is fine while the pipeline refuses it.

### Validation violations are read from the exception, not re-derived

`ManifestService.resolveForBuild` already parses **and** validates, and
`ManifestValidationException` already carries every violation rather than the first. The checker
catches it and reads `getViolations()`. No rule is restated here, and `ManifestValidator` stays the
only place a rule is written.

### Check ids are stable; wording is not

Every finding carries a `checkId` from a fixed enum. The portal's readiness checklist is tied to
these ids by a test, so a message can be reworded freely without silently detaching the guide from
the checks the API runs. This is the mechanism that keeps requirement 3 true over time.

### Severity has three levels, and the middle one matters

- **ERROR** blocks a deploy. Anything `ManifestValidator` rejects, plus a repository the token
  cannot tag.
- **WARNING** deploys but will probably misbehave. The important case is a sidecar with no
  `health:` — see below.
- **INFO** states an assumption, such as "no `poc.yaml`, so a single ingress container named `app`
  was assumed".

`ready` is defined as "no ERROR finding". Warnings deliberately do not block, because they describe
things that genuinely deploy.

### The sidecar health warning is the one non-obvious finding

`CloudRunDeployCommandBuilder.addDependsOnArg` emits `--depends-on` only for sidecars that declared
**both** `health:` and `port`. Cloud Run rejects a dependency on a container with no startup probe,
so this is not an arbitrary coupling.

The consequence a team cannot guess: a sidecar without `health:` still deploys, but the ingress no
longer waits for it, so a cold start can hit a backend that is not listening yet and return 502.
Startup ordering is not a key a manifest can ask for — it is bought by declaring `health:`. The
checker reports this as a WARNING with that explanation, because `ManifestValidator` does not
reject it and should not.

### Dockerfile existence is checked here, not in the validator

`ManifestValidator` validates the manifest's shape and never touches GitHub, which is correct — it
runs in contexts with no network. The checker additionally resolves each container's declared
`dockerfile` path via `GitHubService.getFileContent`. A missing Dockerfile currently surfaces as a
Cloud Build failure several minutes into a deploy; catching it here costs at most 8 requests (the
`manifest.max-containers` ceiling).

Worth stating in the guide alongside it: `dockerfile` and `context` are **independent paths from the
repo root**. `dockerfile` is not relative to `context`, and that trips people up.

## API Surface

`POST /api/v1/poc-onboarding/check` — authenticated, any signed-in user.

Request:

```json
{ "githubUrl": "https://github.com/acme/contract-agent", "slug": "contract-agent" }
```

`slug` is optional. Omitted means "not decided yet", and the availability check is skipped rather
than reported as a failure.

Response:

```json
{
  "repository": "acme/contract-agent",
  "ready": false,
  "manifestPresent": true,
  "findings": [
    {
      "checkId": "SIDECAR_HEALTH",
      "severity": "WARNING",
      "title": "Sidecar 'api' declares no health path",
      "detail": "The ingress will not wait for it to start, so a cold start can return 502.",
      "fix": "Add 'health: /healthz' to the api container in poc.yaml."
    }
  ]
}
```

Schemas live in `openapi/components/schemas/poc-onboarding.yaml`; the path is in
`openapi/self-service-api.yaml`. Controllers are OpenAPI-generated, so both are edited before any
Java is written.

### Check ids

| checkId | Severity when it fires | What it means |
|---------|------------------------|---------------|
| `REPO_URL` | ERROR | The URL is not a recognizable GitHub repo URL |
| `REPO_ACCESS` | ERROR | Not found, or not visible to the configured token |
| `REPO_ARCHIVED` | ERROR | Archived, therefore read-only, so no release tag can be created |
| `REPO_PUSH_ACCESS` | ERROR | Token can read but not push; a public repo is readable by anyone and writable only by collaborators |
| `MANIFEST_ABSENT` | INFO | No `poc.yaml`; the single-container default was assumed |
| `MANIFEST_PARSE` | ERROR | `poc.yaml` is not well-formed YAML, or not shaped like a manifest |
| `MANIFEST_VALIDATION` | ERROR | One finding per `ManifestValidator` violation |
| `DOCKERFILE_PRESENT` | ERROR | A declared `dockerfile` path does not exist at the deploy branch head |
| `SIDECAR_HEALTH` | WARNING | Sidecar has no `health:`, so it loses `--depends-on` ordering |
| `SLUG_AVAILABLE` | ERROR | The requested slug is already taken |

## Data Model

None. The checker reads GitHub and the existing `pocs` table (slug uniqueness) and writes nothing.
No record of a check is persisted — deliberately, since a check is a question about a repository at
one moment, not an entity with a lifecycle.

## Security Considerations

- **Authenticated, but not admin.** Deliberate, and the one place this spec loosens an existing
  boundary. The content is architecture guidance about the platform's own deploy model, not secrets.
- **Response contents are bounded.** Findings, the repository's owner/name, and violations derived
  from the manifest the team wrote. No file contents are returned, including the Dockerfiles the
  checker reads to prove they exist.
- **It is an authenticated GitHub fetch triggered by user input, so it is rate limited per user.**
  `OnboardingRateLimiter` allows 10 checks per user per minute; over that the controller answers
  429. Each check costs several GitHub calls — the repository, the branch head, `poc.yaml`, and one
  per declared Dockerfile — all against the single shared platform token, so an uncapped endpoint
  would let one person holding down a button spend the whole installation's GitHub rate limit and
  break every deploy.

  The limiter is **in memory, and therefore per instance**: with several instances running, the
  effective limit is 10 times the instance count. That is a real weakness, accepted rather than
  hidden. It still bounds a single client by a small constant, and the alternative is a shared store
  this application does not otherwise need. Move it to the database or a cache alongside the first
  other endpoint that needs limiting, not before.
- **A 404 is deliberately not disambiguated.** GitHub returns 404 for a private repository the token
  cannot see, exactly as for one that does not exist, and the finding preserves that ambiguity
  rather than confirming existence to a caller who may not look.

## Implementation Status

Both halves are built: the API endpoint here, and the guide page plus checker panel in
`self-service-portal`.

| Piece | State |
|-------|-------|
| `openapi/components/schemas/poc-onboarding.yaml` | Done |
| `/poc-onboarding/check` path in `openapi/self-service-api.yaml` | Done |
| `GitHubService.checkPushAccess` + `RepoAccess`, `requirePushAccess` refactored onto it | Done |
| `onboarding/OnboardingFinding`, `onboarding/OnboardingCheckResult` | Done |
| `onboarding/PocOnboardingCheckService` | Done |
| `onboarding/PocOnboardingController` | Done |
| `onboarding/OnboardingRateLimiter` | Done |
| Tests | Done — 13 across 3 classes |
| Portal guide page and checker panel | Done — `host-poc-guide`, `repo-check-panel` and six shared primitives in `self-service-portal` |

### Verification

`./mvnw.cmd -DskipITs test` — 337 tests pass, including:

- `PocOnboardingCheckServiceTest` (9) — asserts the checker reports the same violations
  `ManifestValidator` produces, including a repo with no `poc.yaml`, a sidecar missing its port, and
  two ingress containers.
- `OnboardingRateLimiterTest` (2) — the window and its expiry.
- `OnboardingCheckIdParityTest` (2) — ties the portal's readiness checklist to the check ids this
  API actually emits, in **both** directions: no checklist row may name an id that does not exist,
  and no check may be performed that the guide never mentions. The second direction is the more
  useful one, since a rule teams are held to without being told is how a self-service flow stops
  being self-service.

  The test reads the portal's content file at
  `../self-service-portal/src/app/components/host-poc-guide/host-poc-guide.content.ts`. If the
  portal is not checked out beside this repository, the file half skips rather than fails — a
  backend-only clone is a normal way to work here — while the enum half still runs, so an id
  removed from the API is always caught. **It is currently skipping**, because the portal page does
  not exist yet.

## Open Questions / Future Work

- **Replace the shared PAT with a GitHub App.** This is the highest-value follow-up by a wide
  margin. Today onboarding a repository needs the platform's GitHub account added as a collaborator,
  and a collaborator invitation must be accepted by the invitee — so every new team still blocks on
  someone from the platform team clicking accept. With an App, a team installs it on their own
  repository themselves: no invite, no acceptance step, no shared credential, and per-repo
  revocation. It removes the last genuinely manual step in onboarding, and would turn
  `REPO_PUSH_ACCESS` from "ask the platform team" into "install the app".
- **Create-from-URL.** One flow that runs this check and then creates the POC on success, so the
  guide ends in a button rather than a form to retype.
- **Surface the checker on the deployment settings page**, so a POC that starts failing later can be
  re-diagnosed without leaving the portal.
- **Map pipeline error strings to fixes.** `PocDeployment.errorMessage` is already shown on failed
  deployments; mapping the common ones to guide anchors turns the most frequent support question
  into a link.
- **Secrets are not supported at all in this phase.** `env` is non-secret only. A POC needing an API
  key is blocked today, and the checker has no finding for it because there is nothing to check
  against. Worth a finding once a secret mechanism exists.

## Changelog

- 2026-09-09 — initial spec, written alongside the OpenAPI contract and the `GitHubService` split.
  Portal-side guide page specified separately in `self-service-portal`.

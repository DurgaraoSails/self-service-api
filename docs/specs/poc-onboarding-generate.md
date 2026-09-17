# POC Onboarding File Generator

## Status

Implemented

## Overview / Purpose

`docs/specs/poc-onboarding-check.md` answers "is this repository ready?" but leaves a team that
answers "no" to write a `poc.yaml` and any missing Dockerfiles by hand, against
`docs/poc-integration-guide.md` and `docs/poc.yaml.template`. That is still a platform-team support
question in disguise: a team stuck on a Dockerfile asks someone who already knows the contract.

`POST /poc-onboarding/generate` closes that gap. Given a repository and branch, it produces a
ready-to-commit `poc.yaml` and any Dockerfiles the repository is missing, reasoned from the
repository's own contents rather than written from a template alone, and validated against the
exact same `ManifestValidator` a real deploy runs before any of it is returned.

This is **not** a second, informal way to write a manifest. Every rule in
`docs/specs/poc-manifest-deployment.md` still applies; this feature only automates producing
something that already satisfies them.

### What this document does not repeat

| For | See |
|-----|-----|
| `poc.yaml` shape, validation rules, multi-container deploy | `docs/specs/poc-manifest-deployment.md` |
| The readiness checker this feature builds on (`/poc-onboarding/check`), check ids, rate limiting pattern | `docs/specs/poc-onboarding-check.md` |
| The full manifest contract, `${...}` placeholders, secrets | `docs/poc-integration-guide.md` §9 |
| The portal's UI for this endpoint, and today's decision to make it admin-only | `self-service-portal`'s `docs/specs/poc-onboarding-guide-page.md` |

## Requirements

1. Produce a `poc.yaml` for a repository that has none, or correct one that fails validation —
   never leave a team with a manifest that would fail the checker.
2. Produce Dockerfiles for any container that declares none, or replace one that depends on a build
   step this platform does not run (e.g. `COPY target/app.jar` with no `RUN mvn package` in the same
   file) — never touch a Dockerfile that is otherwise fine.
3. **A secret value read from the repository must never appear in a prompt, a log line, a notice, an
   import summary, or any response field.** This is the one requirement every other design decision
   below is subordinate to.
4. Never paste a team's own `cloudbuild.yaml` into a prompt, and never execute it. Read it
   deterministically for facts (build steps, `gcloud run deploy` flags) instead.
5. Degrade to a usable answer, never a 500, whether or not a model is reachable. A repository shape
   the platform can resolve on its own still gets a real answer with no model running at all.
6. Every manifest returned must have already passed `ManifestValidator` — the same guarantee
   `docs/specs/poc-onboarding-check.md` requirement 3 makes for the checker.
7. Cost-bound: an LLM call is categorically more expensive than a GitHub read, so this is rate
   limited more strictly than the checker, and a run spends at most a small, fixed number of model
   calls regardless of repository size.

## Architecture Decisions

### A staged pipeline, not one large prompt

```
1. Check and resolve            (reuses PocOnboardingCheckService; any GitHub failure past this
                                  point degrades to UNAVAILABLE rather than a 500)
2. Scan                         listTree once; RepoLayout is a pure function of the tree
3. Import                       CloudBuildImporter reads cloudbuild*.yaml -> redacted CloudBuildImport
4. Container plan (first that applies):
   - a valid poc.yaml already exists       -> use its containers; poc.yaml is never rewritten
   - a single root Dockerfile and nothing else -> the platform's synthesized default; no model call
   - the draft model                       -> manifest-only, given FACTS + redacted evidence
   - model unavailable or failing          -> DeterministicContainerPlanner fallback
5. Dockerfiles                  DockerfilePlanner decides KEEP / CREATE-from-template /
                                 CREATE-via-model / REPLACE per container, independently of how
                                 step 4 produced the container plan
6. Render + validate             write poc.yaml, parse it back, run it through ManifestValidator
7. Outcome                       NOT_NEEDED / GENERATED / UNAVAILABLE
```

Splitting import, container planning and Dockerfile planning into separate stages, rather than one
prompt that reasons about all of it, is what makes steps 2–3 and 5 fully testable with no model
running (`RepoLayoutTest`, `CloudBuildImporterTest`, `DockerfilePlannerTest`), and what lets step 4
degrade at exactly the point that needs a model, not earlier or later.

### FACTS are authoritative; the model drafts, Java writes

`ManifestDraftService` never asks a model for YAML text. It sends a `FACTS` block
(`ManifestFacts`: per-directory detected stack, whether a Dockerfile already exists there, plus the
cloudbuild import) that the system prompt tells the model to treat as ground truth, plus a bounded,
redacted evidence set (`RepoInventoryService`, capped at 12 files / 200KB — independent of and in
addition to the model's own context-window setting, so neither guard alone can be undone by
misconfiguring the other). The model returns structured JSON (containers, not YAML), which
`ManifestYamlWriter` renders by hand — the same "explicit field-by-field code, not a generic
serializer" preference `ManifestParser` already established, and the only way to reproduce
`docs/poc.yaml.template`'s inline comments at all.

Every draft is run through the real `ManifestValidator` before it is accepted; on a violation, the
violations are sent back verbatim in a corrective follow-up prompt, at most twice more. A draft that
still fails after every attempt becomes `ManifestDraftValidationException`, caught by the
orchestrator and turned into `UNAVAILABLE` — never a manifest that did not pass the validator every
other path in this platform trusts.

### `cloudbuild.yaml` is imported deterministically, never sent to a model

A team's `cloudbuild.yaml` routinely carries secret literals, and its own `RUN`/build steps are not
something this platform should execute. `CloudBuildImporter` reads it with a small, non-executing
shell tokenizer (`ShellWords`) plus hand-written flag tables for `docker build`, `kaniko` and
`gcloud run deploy` (`ImageBuildParser`, `GcloudRunDeployParser`), and produces a `CloudBuildImport`:
structural facts (build context, image, ports, resources, scaling) and secret **names**
(`--set-secrets`), never values. That import is then:

- overlaid onto the model's draft (`ManifestMerger`) — imported settings win over what the model
  guessed, every override reported as a notice — before every validation attempt, not just the last
  (one repair loop covers both what the model got wrong and what the import demands);
- fed to the deterministic fallback (`DeterministicContainerPlanner`) when a single, fully-resolved
  service is all the import contains, so a repository with a complete `cloudbuild.yaml` and no
  reachable model still gets a real container plan;
- reported back to the caller as its own `imports[]` field, separate from `files[]`/`notices[]`, so
  a team can see exactly what was read even when it changed nothing (e.g. a valid `poc.yaml` already
  exists, in which case the import is reported but never applied — `IMPORT_NOT_APPLIED`).

Multiple `gcloud run deploy` calls in one file are merged into one service with sidecars, ports
assigned from 8081 up — this platform runs one Cloud Run service, never several.

### Privacy is enforced by construction, at every boundary that could leak a value

Requirement 3 is not a single check; it is four independent things that all have to hold:

1. **`EvidenceRedactor`** strips `KEY=value`/`KEY: value`-shaped lines and known token/key
   *shapes* (not just names with "secret" in them) from every file's content immediately before it
   reaches a prompt — never at rest, so nothing downstream can accidentally read the unredacted
   version.
2. **`SecretHeuristics`** flags a file whose *unredacted* content still looked like a committed
   credential, turned into an `EVIDENCE_SECRET_REDACTED` notice naming the file, never the value.
3. **`EnvVarClassifier`**, used by the `cloudbuild.yaml` importer, runs every raw environment
   variable through a 7-rule cascade (invalid name → `PORT` → platform-reserved → looks-like-a-secret
   → unresolved `${...}` substitution → cross-service URL → literal) and only ever emits a secret's
   **name** under `requires:`, never a value.
3. **The system prompt** itself instructs the model: a value already replaced with the literal text
   `<redacted>` must never be copied into a response; if a container needs it, declare the variable
   **name** under `requires:` instead.

`ManifestDraftServiceTest.secretLiteralsInEvidenceBecomeNoticesNeverManifestValues` and the
cloudbuild importer's own `toString()` test are the regression guards for this requirement
specifically — see Verification below.

### Dockerfile decisions are deterministic-first, model-fallback, budget-bounded

`DockerfilePlanner` runs one rule per container, entirely independent of whether the container plan
itself came from a model:

| Dockerfile state | Stack recognized (`StackDetector`) | Stack not recognized |
|---|---|---|
| Missing | CREATE from a vetted template (`DockerfileTemplates`), `source: TEMPLATE`, `needsReview: false` | CREATE via `DockerfileDraftService` (one model call), `source: MODEL`, `needsReview: true` |
| Exists, lints clean (`DockerfileLinter`) | KEEP — no file in the response, just a notice if `.dockerignore` is missing | KEEP |
| Exists, depends on a build step this platform does not run (`DOCKERFILE_EXTERNAL_ARTIFACT`) | REPLACE from template | REPLACE via model, one repair attempt on a lint ERROR |

`StackDetector` recognizes 15 stacks from marker files alone (`package.json`'s dependencies,
`pom.xml`, `go.mod`, ...) — no model call. Every CREATE/REPLACE from a template also emits a
matching `.dockerignore`, and, for the nginx-served SPA stacks, the shared nginx config those
templates `COPY` into the image — neither generated when one already exists at that path
(`FILE_EXISTS_NOT_OVERWRITTEN` instead).

`poc-generator.max-dockerfile-model-calls` (default 2) bounds how many of these one generation run
may spend, shared across every container in the run — independent of `ManifestDraftService`'s own
manifest-repair budget, since a repository with several unrecognized components could otherwise turn
one click into many model calls.

### Three outcomes, and `UNAVAILABLE` is never a 500

- **NOT_NEEDED** — the platform already covers this repository (a single root Dockerfile with
  nothing else, or an existing `poc.yaml` that already passes the checker) — `files: []`.
- **GENERATED** — at least one file was produced. Includes the "only a Dockerfile was missing, the
  existing `poc.yaml` was untouched" case — `manifestWasCorrected` distinguishes a correction from a
  fresh manifest.
- **UNAVAILABLE** — generation is turned off, no model is reachable and the deterministic fallback
  could not resolve this repository's shape either, every repair attempt still failed validation, or
  a GitHub read failed mid-run. `files` still carries the platform's own `docs/poc.yaml.template` in
  this case (via a build-time `maven-resources-plugin` copy onto the classpath — the file lives
  outside `src/main/resources` in git), so there is always something to copy. This mirrors
  `docs/specs/poc-onboarding-check.md`'s "an unready repository is a 200, not a 4xx" decision:
  generation not running is an expected outcome, not a caller error.

## API Surface

`POST /poc-onboarding/generate` — authenticated. (See `self-service-portal`'s
`docs/specs/poc-onboarding-guide-page.md` for why the *portal* only exposes this to admins today —
the API itself imposes no stricter check than "signed in", matching `/poc-onboarding/check`.)

Request:

```json
{ "githubUrl": "https://github.com/acme/contract-agent", "deployBranch": "main" }
```

Response (shape only — every field is documented in
`openapi/components/schemas/poc-onboarding.yaml`):

```json
{
  "outcome": "GENERATED",
  "files": [
    {
      "path": "poc.yaml",
      "kind": "POC_YAML",
      "action": "CREATE",
      "source": "MODEL",
      "container": null,
      "content": "...",
      "reason": "Generated from the repository.",
      "needsReview": false
    },
    {
      "path": "apps/api/Dockerfile",
      "kind": "DOCKERFILE",
      "action": "CREATE",
      "source": "TEMPLATE",
      "container": "api",
      "content": "...",
      "reason": "Detected PYTHON_FASTAPI from apps/api/requirements.txt.",
      "needsReview": false
    }
  ],
  "notices": [
    { "severity": "INFO", "code": "IMPORT_OVERRODE_DRAFT", "message": "...", "container": "api" }
  ],
  "imports": [],
  "assumptions": ["Treated 'api' as ingress: the only container with a published port."],
  "manifestWasCorrected": false,
  "checkResult": { "...": "the same shape /poc-onboarding/check returns" }
}
```

`files`, `notices` and `imports` are the current, complete shape of a run's result. `pocYaml`,
`dockerfiles` and `warnings` are kept on the wire (`deprecated: true` in the OpenAPI spec) as
**derived views** over the same data — `PocManifestGenerationResult.pocYaml()`/`dockerfiles()`/
`warnings()` compute them from `files`/`notices` on every call rather than storing a second copy —
so an older caller keeps working without this endpoint tracking two sources of truth.

### Notice codes

The full, additive vocabulary is `GenerationNoticeCode` (a plain string, not an enum on the wire, so
a new code is never a breaking change). Grouped by what emits them:

| Source | Codes |
|---|---|
| Evidence / import | `TREE_TRUNCATED`, `EVIDENCE_SECRET_REDACTED`, `MODEL_UNAVAILABLE` |
| `cloudbuild.yaml` import | `CLOUDBUILD_IMPORTED`, `CLOUDBUILD_PARSE_FAILED`, `CLOUDBUILD_STEP_NOT_UNDERSTOOD`, `CLOUD_RUN_YAML_NOT_IMPORTED`, `OTHER_DEPLOY_TARGET`, `IMPORT_NOT_APPLIED`, `IMPORT_OVERRODE_DRAFT`, `SERVICES_MERGED`, `SUBSTITUTION_UNRESOLVED`, `ENV_FILE_NOT_IN_REPO` |
| Env var classification | `INVALID_ENV_NAME`, `RESERVED_ENV_DROPPED`, `SECRET_VALUE_DROPPED`, `SECRET_TO_PROVISION` |
| Import coverage gaps | `UNSUPPORTED_SETTING`, `FILE_SECRET_UNSUPPORTED`, `SIDECAR_RESOURCES_UNSUPPORTED`, `BUILD_ARG_UNSUPPORTED`, `BUILD_TARGET_UNSUPPORTED`, `BUILDPACKS_NEEDS_DOCKERFILE` |
| Dockerfile linting | `DOCKERFILE_NO_FROM`, `DOCKERFILE_EXTERNAL_ARTIFACT`, `DOCKERFILE_LOCALHOST_BIND`, `DOCKERFILE_FIXED_PORT`, `DOCKERFILE_SECRET_IN_IMAGE`, `DOCKERFILE_ROOT_USER`, `DOCKERFILE_NO_DOCKERIGNORE`, `DOCKERFILE_EXPOSE_MISMATCH`, `DOCKERFILE_UNRESOLVED` |
| File planning | `FILE_EXISTS_NOT_OVERWRITTEN`, `TOO_MANY_COMPONENTS` |

## Data Model

None. Same as the checker: reads GitHub and writes nothing. A generation run is a question about a
repository at one moment, and its answer is meant to be copied into the repository by a human, not
stored as a platform record.

## Security Considerations

- **Authenticated, not admin, at the API layer** — matching `/poc-onboarding/check`. The *portal*
  additionally restricts its UI for this endpoint to admins; see the cross-reference above.
- **Requirement 3 (never leak a secret value) is the one requirement this whole design answers
  to** — see "Privacy is enforced by construction" above for the four independent places it is
  enforced, and Verification below for how it is tested.
- **`cloudbuild.yaml` is read, never executed.** `ShellWords` tokenizes shell syntax without ever
  invoking a shell; nothing in the importer shells out.
- **Rate limited more strictly than the checker**, because an LLM call is categorically more
  expensive: `OnboardingRateLimiter.Purpose.GENERATE` allows 3 generations per user per 5 minutes
  (vs. 10 checks per minute for `Purpose.CHECK`), enforced against the same in-memory, per-instance
  limiter documented in `docs/specs/poc-onboarding-check.md` (same accepted weakness: the effective
  limit multiplies by instance count).
- **Bounded, independent of repository size, in three separate places**: `RepoInventoryService`
  caps evidence at 12 files / 200KB regardless of `numCtx`; `ManifestDraftService` repairs at most
  twice; `poc-generator.max-dockerfile-model-calls` (default 2) bounds Dockerfile-drafting calls per
  run. A pathological repository cannot turn one request into an unbounded number of model calls or
  an unbounded prompt.
- **Never a 500 for "no model configured/reachable."** `poc-generator.enabled=false`, an
  unconfigured provider, a `ManifestDraftException` from a live call, and every repair attempt
  failing all resolve to `UNAVAILABLE` with the shipped template — this was an explicit design
  requirement (a local checkout with no Ollama running and no GCP credentials must not break the
  endpoint) and is covered by
  `PocManifestGenerationServiceTest.aDraftModelCallFailureFallsBackToTheShippedTemplateWhenTheDeterministicFallbackAlsoCannotResolveIt`.

## Implementation Status

| Piece | State |
|---|---|
| `openapi/components/schemas/poc-onboarding.yaml` — `PocManifestGenerate{Request,Response}`, `PocGeneratedFile`, `PocGenerationNotice`, `PocGenerationImport` | Done |
| `RepoLayout`, `RepoFileReader`, `GenerationNotice(Code)` | Done |
| `secret/SecretHeuristics`, `EvidenceRedactor`, `EnvVarClassifier` | Done |
| `stack/StackKind`, `DetectedStack`, `StackDetector` (15 stacks) | Done |
| `dockerfile/DockerfileTemplates` + 15 stack templates + shared nginx config | Done |
| `dockerfile/DockerfileLinter` (8 rules), `DockerfileDraftService`, `DockerfilePlanner` | Done |
| `cloudbuild/ShellWords`, `CloudBuildConfigParser`, `StepCommandExtractor`, `ImageBuildParser`, `GcloudRunDeployParser`, `CloudBuildImporter` | Done |
| `ManifestFacts`, `ManifestMerger`, `DeterministicContainerPlanner` | Done |
| `ManifestDraftService` (manifest-only, FACTS + redacted evidence, repair loop) | Done |
| `ManifestYamlWriter` | Done |
| `PocManifestGenerationService` (7-step pipeline) | Done |
| `model/OllamaDraftModel`, `VertexDraftModel`, `DraftModelProperties` (incl. `max-dockerfile-model-calls`) | Done |
| `OnboardingRateLimiter.Purpose.GENERATE` | Done |
| Portal UI (`repo-check-panel`, `generated-file-card`) | Done — see `self-service-portal`'s spec |

### Verification

`./mvnw.cmd -DskipITs test` — 715 tests pass. The ones specific to this feature:

- `ManifestMergerTest`, `DeterministicContainerPlannerTest`, `DockerfilePlannerTest`,
  `DockerfileDraftService` (exercised via `DockerfilePlannerTest`'s stub-model cases) — the
  deterministic collaborators, each testable with no model running.
- `ManifestDraftServiceTest` — the repair loop against a stub `ManifestDraftModel`, including the
  regression guard that the shared JSON schema never reintroduces a `{"type": [...]}` array (Vertex
  rejects it outright — see `docs/specs/poc-manifest-deployment.md`'s adapter notes if one exists,
  or the class javadoc on `ManifestDraftService` otherwise) and
  `secretLiteralsInEvidenceBecomeNoticesNeverManifestValues`, the direct test of requirement 3.
- `CloudBuildImporterTest` (12 cases) — end-to-end against 10 fixture `cloudbuild.yaml` files,
  including the secret-name-only extraction and its privacy-preserving `toString()`.
- `PocManifestGenerationServiceTest` — the orchestration matrix: valid `poc.yaml` untouched, a valid
  `poc.yaml` naming a missing Dockerfile generating just that file, a fresh multi-component repo via
  the model, correcting an invalid existing manifest, the deterministic fallback succeeding and
  failing, a live model-call failure, and a GitHub failure mid-run — all against real deterministic
  collaborators (only `GitHubService`, `ManifestService`, `PocOnboardingCheckService`,
  `RepoInventoryService` and the manifest-level `ManifestDraftService` are mocked), so a mocked
  `poc.yaml` never has to be hand-crafted to survive this class's own re-validation step.

No end-to-end run against a live Ollama/Vertex has been exercised while writing this spec — every
test above records a realistic request/response shape and runs the repair loop against a stub model
instead. A real run against `poc-integration-testbed` or `poc-multiservice-testbed` (which have
committed manifests to compare against) is the next useful check once a model is available in the
environment this is verified from.

## Open Questions / Future Work

- **No live-model end-to-end run yet** — see Verification above.
- **Delivery is copy/download only, no auto-PR.** A team reviews and commits by hand. Opening a PR
  automatically was considered and deliberately deferred — this feature already makes an LLM-drafted
  change to a repository; writing that change without a human review step in between is a larger
  trust decision than generating a draft to review.
- **`cloudbuild.yaml` merging across multiple files is not attempted.** Only the first candidate
  file found is read; a repository with several cloudbuild configs that might disagree is treated as
  having one, arbitrarily. Rare enough in practice that this was accepted rather than designed for.
- **The portal made this admin-only today**, not because the API requires it, but to bound LLM-cost
  exposure and keep the feature behind a smaller, trusted surface while it is new. See
  `self-service-portal`'s spec for the reasoning and the option to widen it later.

## Changelog

- 2026-09-17 — initial spec, written after the feature (Phases 0–3 of the original implementation
  plan) was already built and its 715-test suite green, to give this repository's `docs/specs/`
  directory the living document its README promises every non-trivial feature. Companion spec in
  `self-service-portal` covers the portal side and today's admin-only decision.

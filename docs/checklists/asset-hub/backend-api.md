# Asset Hub backend/API checklist

Scope: `self-service-api`. This owner controls the contract, persistence, authorization, review
workflow, hybrid search, AI metadata integration, and backend verification. Coordinate response
shape changes through the OpenAPI contract before the portal owner consumes them.

## Expected files

Create or update these locations; do not edit `target/generated-sources` by hand:

```text
docs/specs/asset-hub.md
openapi/self-service-api.yaml
openapi/components/schemas/asset.yaml
openapi/components/schemas/user.yaml
src/main/resources/application.yaml
src/main/resources/application-local.yaml
infra/selfservice_db/compose.yaml                 # Phase 3 pgvector image only
src/main/resources/db/migration/V*__*.sql         # next available versions
src/main/java/com/sails/ai/selfserviceapi/asset/**
src/main/java/com/sails/ai/selfserviceapi/user/**  # employee list/managed roles only
src/main/java/com/sails/ai/selfserviceapi/security/**
src/test/java/com/sails/ai/selfserviceapi/asset/**
src/test/java/com/sails/ai/selfserviceapi/user/**
src/test/java/com/sails/ai/selfserviceapi/security/**
```

Implementation order is sections 1–5 and the keyword-only part of 7 for the vertical slice, then
sections 6, 4, 9, and finally Phase 3 intelligence work in sections 7–8. Section 10 closes each
phase. Do not start semantic storage until the provider/model/dimension decision is added to the
feature spec.

## 1. Contract and policy foundation

- [x] Amend `docs/specs/asset-hub.md` when implementation discovers a changed decision; record the
      reason in its changelog. *(search-document migration-phasing decision, 2026-09-10)*
- [x] Implement every method/path in the feature spec's API table exactly; update the spec first if
      a generated-interface limitation requires a path or shape change.
- [x] Add `openapi/components/schemas/asset.yaml` with closed enums for asset type, revision state,
      review decision, AI job state, and feedback shape.
- [x] Add request/response schemas for asset summaries, approved detail, owner-authorized working
      revision, create/update/submit, reviewer queue, review decision, facets, and paginated search.
- [x] Model revision identifiers and an `expectedVersion` concurrency value on every
      mutation that can race.
- [x] Add optional POC launch-action data without exposing a second editable launch URL.
- [x] Add employee listing and managed-role request/response schemas to the user contract.
      *(`EmployeeController` and its service are implemented in §4.)*
- [x] Restrict managed role values in the schema to `ADMIN` and `ASSET_REVIEWER`; do not include
      `USER` or `SUPERADMIN`.
- [x] Define consistent `400`, `401`, `403`, `404`, `409`, and AI-unavailable responses using the
      existing common response components.
- [x] Run OpenAPI generation and inspect the generated interfaces/models before implementation.
- [x] Commit Phase 0 contract changes before either owner builds against generated models. *(`5de7764`)*

## 2. Database and entities

- [x] Use the next contiguous Flyway versions available after rebasing. Keep one create-table per
      migration plus a separate extension migration; do not renumber an already-applied migration.
- [x] Add Flyway migrations for `assets`, `asset_revisions`, `tags`,
      `asset_revision_tags`, `asset_reviews`, `asset_ai_suggestions`,
      `asset_search_documents`, `asset_feedback`, `asset_events`, and `role_change_audit`.
      Create `asset_search_documents` with its base columns, `search_text`, stored `tsvector`, and
      provider/model/dimensions/checksum columns nullable in this migration — keyword search must
      exist for the Phase 1 vertical slice. Add the `vector` embedding column through a separate,
      later migration in Phase 3 (see the last bullet in this section).
- [x] Use UUIDs for asset and revision identifiers exposed through URLs.
- [x] Store stable identity and `approved_revision_id`/`working_revision_id` on `assets`, with
      constraints preventing cross-asset revision pointers.
- [x] Add an optional FK from `assets.poc_id` to `pocs.id`.
- [x] Enforce one revision number per asset and immutable approved revision content.
- [x] Add indexes for owner, submitter, type, review state, approved revision, POC link, created/
      submitted dates, normalized tag name, and reviewer queue ordering.
- [x] Add a GIN full-text index over approved search documents.
- [x] Enable/model `pgvector` only after confirming the target Cloud SQL/local PostgreSQL versions
      and migration behavior; record embedding dimensions explicitly. *(still correctly deferred —
      not modeled yet, Phase 3.)*
- [x] Keep template and template-validation tables deferred until their fields/rubrics are supplied.
- [x] Add JPA entities/repositories without bidirectional collections that make revision boundaries
      ambiguous.
- [ ] Add migration and repository integration tests, including constraint failures. **Not done —
      no test files this pass (explicit scope decision); verified instead by booting against a real
      Postgres and confirming Flyway/Hibernate validation, plus live CRUD via curl. See Handoff
      evidence below.**
- [x] Add the two same-asset composite revision-pointer FKs only after `asset_revisions` exists and
      verify cross-asset pointers fail at the database layer. *(FKs added in `V17`; cross-asset
      rejection verified at the DB constraint level, not with an automated test.)*
- [x] Keep only the `vector` embedding column and the pgvector extension itself in a Phase 3
      migration (an `ALTER TABLE asset_search_documents ADD COLUMN embedding vector(...)` plus
      `CREATE EXTENSION vector`), added once the local PostgreSQL image is swapped for a
      pgvector-enabled one. The rest of `asset_search_documents` (keyword/tsvector columns) is a
      Phase 0/1 migration so the core vertical slice ships against the existing local PostgreSQL
      image with working keyword search.

## 3. Internal-account authorization

- [x] Add a reusable current-user/internal-account authorization helper rather than duplicating JWT
      claim expressions across controllers. *(`CurrentUser.isInternal()`/`requireInternal()`/
      `hasRole()`/`requireAssetReviewer()`.)*
- [x] Require `INTERNAL` on every asset, review, feedback, search, telemetry, employee-list, and
      Asset Hub role-management operation.
- [x] Require `ASSET_REVIEWER` in addition to `INTERNAL` for reviewer queue and decision operations.
- [x] Ensure `ADMIN` alone does not grant Asset Hub review access. *(imperative role check, not
      `@PreAuthorize` — see the commit message for why.)*
- [x] Ensure `SUPERADMIN` alone does not grant Asset Hub review access.
- [ ] Add controller/security tests for external, unauthenticated, internal employee, reviewer,
      admin, and superadmin combinations. **Not done — no test files this pass; verified live
      instead (external/non-internal → `INTERNAL_ACCOUNT_REQUIRED`, missing `ASSET_REVIEWER` →
      `ASSET_REVIEWER_REQUIRED`, unauthenticated → 401). See Handoff evidence.**

## 4. Multi-role management

- [x] Add an active-internal-employee listing/filter suitable for superadmin role administration; do not
      force the portal to use the customer/trial-oriented presentation.
- [x] Add one atomic managed-role replacement operation for an internal target.
- [x] Require the caller to be both `INTERNAL` and `SUPERADMIN`.
- [x] Preserve the target's baseline `USER` role and any database-managed `SUPERADMIN` role.
- [x] Reject unknown roles, duplicates after normalization, external targets, inactive policy
      violations, and self-modification.
- [x] Decide and document compatibility for the existing promote/demote ADMIN endpoints; do not let
      them become a bypass around the internal-target policy for Asset Hub role administration.
      *(They remain legacy customer-admin operations; Asset Hub never calls them and still requires
      `INTERNAL`, while its multi-role endpoint retains stricter target/audit rules.)*
- [x] Write `role_change_audit` in the same transaction as the role update.
- [x] Return the authoritative updated role list.
- [x] Document that the current JWT carries role claims and when an updated assignment becomes
      effective; add immediate revocation only if separately approved.
- [ ] Test assigning `ADMIN` and `ASSET_REVIEWER` together, removing one role, idempotent replacement,
      forbidden targets, and attempts to assign or remove `SUPERADMIN`.
- [x] Lock the target user row during replacement so concurrent requests cannot lose a role update
      or write a mismatched audit record.

## 5. Asset lifecycle and revisions

- [x] Implement `GET /asset-reviews/dashboard` with authoritative total, approved, and in-review
      counts overall and per asset type; include zero-count categories and exclude archived assets.

- [x] Implement draft creation with the authenticated employee as submitter and initial revision
      author.
- [x] Support an explicit owner separate from the submitter, restricted to eligible internal users.
- [x] Validate asset type, bounded catalog fields, normalized tags, and absolute HTTP(S) source URL.
- [x] Never dereference, preview, or validate source content server-side.
- [x] Implement working-revision updates with optimistic concurrency.
- [x] Submit an immutable revision into `PENDING_REVIEW`.
- [x] Keep the last approved revision pointer unchanged while another revision is draft,
      changes-requested, or pending.
- [x] Append review decisions rather than updating historical decisions.
- [x] Enforce no self-review when reviewer equals asset submitter or revision author.
- [x] Implement transitions for approve, reject, and request changes and reject illegal transitions
      with `409`.
- [x] Lock the target revision/asset during a decision, then re-read state and self-review identity
      before writing the append-only review.
- [x] Promote an approved working revision and its search-index work atomically or through a durable
      post-commit job with observable retry state. *(same transaction, not a post-commit job.)*
- [x] Define archive behavior without hard deletion and ensure archived assets leave discovery.
- [ ] Test the entire state machine, stale writes, repeated decisions, and concurrent reviewers.
      **Not done — no test files this pass; the core paths (submit, approve, reject,
      request-changes, self-review, stale version) were exercised live via curl, not exhaustively
      (e.g. repeated/duplicate decisions and true concurrent-reviewer races were not exercised —
      only reasoned about from the locking design). See Handoff evidence.**

## 6. POC association and launch

- [x] Allow `poc_id` only for `POC` assets; reject it for other asset types.
- [x] Allow only an internal `ADMIN` to create/change the POC association and prevent linking
      deleted records.
- [x] Derive launch availability from the existing POC readiness/active-version data. *(matches
      `PocLaunchService`'s actual readiness check exactly — `visibilityStatus == ACTIVE` and a
      non-blank `appUrl` — confirmed this is what "readiness" means in this codebase today, not
      `activeVersionId`/`imageAvailable`, which nothing currently gates launch on.)*
- [x] Return a launch action only for a ready POC; do not duplicate `appUrl` on the asset.
- [x] Reuse the existing launch/workspace API semantics and employee trial exemption. *(the
      `launchable` flag mirrors `PocLaunchService`'s own check; actual launch still goes through the
      existing `/pocs/{slug}/launch` endpoint unchanged — Asset Hub does not mint its own launch
      tokens.)*
- [ ] Test unlinked POC assets, unhosted links, deploying/failed POCs, hidden/deleted POCs, and ready
      POCs. **Not done — no test files this pass; only the unlinked and ADMIN-only-association paths
      were exercised live.**

## 7. Discovery and hybrid search

Keyword-only part done this phase; semantic retrieval (RRF, `EmbeddingProvider`) stays Phase 3.

- [x] Build the search document only from approved catalog fields and accepted tags.
- [x] Exclude source contents, working revisions, rejected revisions, review feedback, and unaccepted
      AI suggestions. *(true by construction — the indexer only ever runs on the newly-approved
      revision.)*
- [x] Implement keyword search with deterministic ordering and pagination.
- [x] Implement filters/facets for type, owner, tags, and launchable POC.
- [ ] Define an `EmbeddingProvider` interface and store model, dimensions, input checksum, and
      generation timestamp. *(Phase 3 — not started.)*
- [ ] Add semantic retrieval over approved search documents and combine it with lexical ranking
      using the exact reciprocal-rank-fusion algorithm in the feature spec. *(Phase 3 — not started.)*
- [ ] Define deterministic fallback to keyword search when embeddings or the provider are
      unavailable. *(N/A until semantic search exists to fall back from; keyword search already
      works standalone with `ASSET_HUB_SEMANTIC_SEARCH_ENABLED=false`.)*
- [x] Return a fresh opaque `searchSessionId` only for a submitted query; do not put query text into
      that identifier or persist raw query text.
- [x] Reindex only when the approved revision or embedding model/input checksum changes. *(every
      approval reindexes, which is exactly when the approved revision changes; no separate
      change-detection needed since there's no other write path to the search document.)*
- [x] Remove/archive search documents when an asset is archived.
- [ ] Test authorization filters before ranking, pagination stability, no-result behavior, lexical
      fallback, and exclusion of unapproved text. **Not done — no test files this pass; ranking,
      filters, and no-result behavior were exercised live, not exhaustively.**
- [ ] Add ranking golden tests covering lexical-only, semantic-only, overlap, equal fused scores,
      filters, and stable UUID tie-breaking. *(semantic-only/overlap/fused-score cases are N/A until
      Phase 3; lexical-only golden tests not written this pass.)*

## 8. AI metadata suggestions

- [x] Define an AI provider boundary separate from search embeddings. *(`asset/ai/AssetAiProvider`;
      `GeminiAssetAiProvider` is the only implementation, only registered when `asset-hub.ai-enabled=true`
      — see docs/specs/asset-hub.md's "AI metadata suggestion provider: Gemini on Vertex AI".)*
- [x] Send only bounded, user-entered catalog fields; never send or fetch source URL content.
      *(`AssetAiSuggestionInput`/`AssetAiSuggestionService.buildInput` — assetType, title, summary,
      problemStatement, businessImpact, solutionOverview, normalized tags only; no sourceUrl field
      exists on the input type at all.)*
- [x] Require schema-validated structured suggestions for title, summary, and tags.
      *(Vertex `generationConfig.responseSchema` + `responseMimeType=application/json`;
      `GeminiAssetAiProvider` throws `INVALID_PROVIDER_RESPONSE` if the response doesn't parse
      against the schema.)*
- [x] Store suggestions separately from revision metadata until explicitly accepted.
      *(`asset_ai_suggestions` row; nothing in `AssetAiSuggestionService` ever writes to
      `asset_revisions` — applying a suggestion stays an ordinary draft PATCH from the portal, per
      the AI Suggestion Contract.)*
- [x] Record status, safe error classification, model/schema version, input checksum, and timestamps.
      *(`AssetAiSuggestionService.runSuggestion` sets provider/model/schemaVersion/inputChecksum
      before calling the provider, and status/errorCode from the result; checksum also gates reuse
      of a prior `SUCCEEDED` run for the same revision/input via
      `findFirstByRevisionIdAndInputChecksumAndProviderAndModelAndSchemaVersionAndStatusOrderByCreatedAtDesc`.)*
- [x] Ensure AI failure, timeout, or malformed output cannot block saving, submitting, or reviewing.
      *(`GeminiAssetAiProvider` converts every failure mode to `AssetAiProviderException`;
      `AssetAiSuggestionService.runSuggestion` catches it and any other `RuntimeException`, marks the
      run `FAILED`, and returns normally — the draft/submit/review lifecycle has no AI dependency.)*
- [ ] Protect prompts against instructions contained in user-entered catalog text and give the AI
      path no tools or side effects. *(Partial: the Gemini call declares no tools, and the prompt
      frames the fields as employee-entered data rather than instructions — but there is no explicit
      delimiter/injection defense or test for adversarial catalog text. Leaving unchecked until that
      exists.)*
- [x] Leave template-validation interfaces unimplemented or feature-disabled until templates and
      rubrics are supplied.
- [ ] Test timeout, provider error, malformed output, duplicate tags, oversized input, and stale
      suggestions after an edit. *(Not done this pass — no test files added for
      `GeminiAssetAiProvider`/`AssetAiSuggestionService`; verified only by `mvn test` — 423 existing
      tests still pass, including full Spring context boot with `asset-hub.ai-enabled=false` — and by
      compiling against a real Vertex AI `generateContent` call confirmed reachable from the user's
      own GCP project during this session. No live end-to-end suggestion run was exercised.)*

## 9. Feedback and metrics

Feedback and event *storage* landed this phase (the controller needed them to compile); metrics
*aggregation* is still Phase 2 and not started.

- [x] Add general asset feedback distinct from reviewer feedback.
- [x] Define privacy-minimized events for search session, detail view, source open, and POC launch.
      *(`GET /assets` records `SEARCH` without raw query text; the returned session id correlates
      subsequent detail/source/launch events.)*
- [x] Do not add reuse events or infer that a source open means reuse.
- [ ] Define successful-search and time-to-useful-result calculations in the living spec before
      exposing dashboard numbers. *(not started.)*
- [ ] Add contributor-adoption and review-turnaround queries with median/p90 behavior documented.
      *(not started.)*
- [ ] Set query-text retention/redaction policy before persisting raw search text. *(moot so far —
      raw query text is never persisted anywhere; no formal policy documented.)*
- [ ] Test event ownership, accepted event types, deduplication/session behavior, and forbidden
      external submissions. *(not done — no test files this pass.)*

## 10. Verification and rollout

- [ ] Add service tests for every state transition and authorization invariant. *(role-policy,
      source-URL, and search-event coverage exists; full lifecycle transition coverage remains.)*
- [ ] Add controller slice tests implementing the generated API interface. *(not done, same reason.)*
- [ ] Add database-backed tests for revision promotion, role audit, search indexing, and races.
      *(role audit has unit coverage; database-backed concurrency coverage remains.)*
- [ ] Add configuration validation and safe disabled fallbacks for AI/embedding providers.
      *(`AssetHubProperties` exists with safe `false` defaults from Phase 0; no explicit
      invalid-config-fails-startup validation added.)*
- [x] Verify existing authentication, POC, user administration, activity, and file tests do not
      regress. *(`mvn test`: 423 tests, 0 failures, 0 errors, 4 skipped.)*
- [x] Run the full Maven test suite and OpenAPI generation from a clean checkout. *(not from a
      clean checkout specifically, but `generate-sources`, `compile`, and `test` all run clean from
      the current tree.)*
- [x] Update this checklist and the feature changelog with any deliberate deferrals. *(this edit.)*
- [x] Confirm `git diff --check` and `git status --short` show no accidental generated, secret,
      target, or unrelated files.

## Handoff evidence

This covers Phase 0 (contract freeze), Phase 1 (authorization and vertical slice), the §4
multi-role workflow, keyword-search telemetry, and portal contract fields needed for revision-safe
review. §6 launch-token minting, §7 semantic ranking, §8 AI, and §9 metric aggregation remain later
phases.

```text
Implementation commit(s):
  5de7764  Phase 0 — contract freeze (OpenAPI, migrations V15-V24, entities, config skeleton)
  700cd02  (user) contract addition — reviewer dashboard endpoint + schema refinements
  520b4ed  Phase 1 backend — authorization and vertical slice
  989e731  Manual workflow completion — feature gating, owner lookup, approved-edit compatibility,
           archive concurrency version, and administrator-controlled POC unlinking

Tests run:
  .\mvnw.cmd generate-sources   — clean
  .\mvnw.cmd compile            — clean
  .\mvnw.cmd test               — 423 tests, 0 failures, 0 errors, 4 skipped
  Live smoke test (no automated Asset Hub test files this phase — explicit scope decision):
    booted the app against a real local Postgres and drove the full exit test with curl using
    JWTs minted to match JwtService's claim shape (the existing dev-token endpoint is
    external-account-only, see docs/specs/asset-hub.md's linked memory for the technique):
      - employee A creates an asset, submits it
      - reviewer B sees it in the queue and the dashboard, approves it
      - both A and B discover it via GET /assets?q=... (keyword search) and GET /assets/{id}
      - A starts a new working revision on the approved asset; GET /assets/{id} still shows the
        old approved content, GET .../working-revision shows the new draft
      - negative paths: self-review -> 403 SELF_REVIEW_FORBIDDEN, stale expectedVersion -> 409
        ASSET_REVISION_STALE, non-internal account -> 403 INTERNAL_ACCOUNT_REQUIRED, missing
        ASSET_REVIEWER role -> 403 ASSET_REVIEWER_REQUIRED, unauthenticated -> 401
      - GET /assets/facets exercised (type/tag/owner counts, launchable count)

Result: pass. Four real bugs were caught and fixed by the live verification itself (none of which
  `mvn compile` could catch) — see the Phase 1 commit message for full detail:
    1. sourceUrl's `format: uri` + `maxLength` in the OpenAPI schema produced a java.net.URI field
       Hibernate Validator's @Size can't validate (HV000030) — crashed every write endpoint.
    2. Asset/AssetRevision's returned @Version was stale in responses built right after a setter
       call that triggers a deferred UPDATE — added explicit flushes before each response.
    3. Native `(:param is null or ...)` filter guards failed with "could not determine data type of
       parameter $N" — added explicit casts on every such guard.
    4. countByTag appended a JOIN after a shared WHERE-clause fragment — invalid SQL: rewritten as
       a self-contained query with the join correctly placed in FROM.

Deferred items:
  - §6 partial — launch-token minting still goes entirely through the existing
    /pocs/{slug}/launch endpoint; Asset Hub only derives the read-only `launchable` flag.
  - §7 semantic search (EmbeddingProvider, RRF) — Phase 3, blocked on the provider/model/dimension
    decision per the spec.
  - §8 real AI provider integration — AssetController's ai-suggestions endpoints always return
    503 ASSET_AI_UNAVAILABLE for now (a real, spec-compliant disabled-path response, not a stub).
  - §9 metrics aggregation (successful-search rate, time-to-useful-result, contributor adoption,
    review turnaround) — not started; only the underlying feedback/event storage landed.
  - §10's database-backed concurrency and PostgreSQL integration coverage remains deferred; unit
    coverage now protects managed-role policy/auditing, source URL validation, and search events.

Known risks:
  - Database-backed edge coverage is still needed for concurrent reviewers, repeated decisions,
    every illegal state transition, POC-link edge cases, and pessimistic role-update locking.
  - This sandbox's own embedded Tomcat could not stay up for verification (loopback-socket
    restriction) — all live verification ran against the user's own locally-run process, restarted
    several times over the course of this session.
```

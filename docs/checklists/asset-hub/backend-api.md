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
- [ ] Add migration and repository integration tests, including constraint failures. **Deliberately
      out of scope, confirmed with the user 2026-09-15: this codebase's entire test suite is
      Mockito-only (no Testcontainers, no DB-backed tests anywhere), and introducing a first
      DB-backed testing convention for just this feature was explicitly declined in favor of
      staying consistent. Verified instead by booting against a real Postgres and confirming
      Flyway/Hibernate validation, plus live CRUD via curl. See Handoff evidence below.**
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
- [x] Add controller/security tests for external, unauthenticated, internal employee, reviewer,
      admin, and superadmin combinations. *(2026-09-15: `security/CurrentUserTest.java` covers
      `isInternal`/`hasRole`/`requireInternal`/`requireAssetReviewer`/`requireSuperAdmin`/`isAdmin`
      across unauthenticated/external/internal/reviewer/admin/superadmin; `AssetControllerTest`,
      `AssetReviewControllerTest`, and `EmployeeControllerTest` (`@WebMvcTest` + MockMvc, matching
      `PocFilesControllerTest`'s pattern) cover the HTTP-layer `403` mapping for each.)*

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
- [x] Test assigning `ADMIN` and `ASSET_REVIEWER` together, removing one role, idempotent replacement,
      forbidden targets, and attempts to assign or remove `SUPERADMIN`. *(`EmployeeRoleServiceTest`
      already covered the replace/self-management/external-target/inactive-target/duplicate-role
      cases; 2026-09-15 added `listEmployees` coverage and `EmployeeControllerTest`'s
      `SUPERADMIN_REQUIRED`/happy-path/service-rejection-propagation cases at the HTTP layer.)*
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
      *(2026-09-15: `AssetLifecycleServiceTest` now covers create/get/getWorkingRevision/
      createWorkingRevision/updateWorkingRevision (including stale-version `409`)/
      submitWorkingRevision/archiveAsset/listMyAssets/putFeedback/recordEvent; `AssetReviewServiceTest`
      covers self-review-by-submitter-or-author, illegal-transition, stale-version, approve
      (supersede + reindex), request-changes/reject, and append-only-not-update. Still not done:
      repeated/duplicate decisions on an already-decided revision, and true concurrent-reviewer
      races — both need a real transactional DB, ruled out of scope for this Mockito-only pass. See
      Handoff evidence.)*

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

Keyword search shipped in Phase 1. Semantic retrieval (RRF, `EmbeddingProvider`) is now scaffolded
(2026-09-15) but not reachable in any deployment — see the two blocked items below for why.

- [x] Build the search document only from approved catalog fields and accepted tags.
- [x] Exclude source contents, working revisions, rejected revisions, review feedback, and unaccepted
      AI suggestions. *(true by construction — the indexer only ever runs on the newly-approved
      revision.)*
- [x] Implement keyword search with deterministic ordering and pagination.
- [x] Implement filters/facets for type, owner, tags, and launchable POC.
- [x] Define an `EmbeddingProvider` interface and store model, dimensions, input checksum, and
      generation timestamp. *(2026-09-15: `asset/ai/EmbeddingProvider` +
      `asset/ai/VoyageEmbeddingProvider`, gated by `asset-hub.semantic-search-enabled` exactly like
      `AssetAiProvider`/`ai-enabled`. `AssetSearchIndexer` records provider/model/dimensions/checksum
      today; the `embedding` vector itself has nowhere to persist to until the migration below lands.)*
- [x] Add semantic retrieval over approved search documents and combine it with lexical ranking
      using the exact reciprocal-rank-fusion algorithm in the feature spec. *(2026-09-15:
      `AssetSearchRepository.findSemanticCandidateIds` + `AssetSearchRankingService.merge`,
      `1/(60+lexicalRank) + 1/(60+semanticRank)`, tie-broken by approved revision `updated_at DESC`
      then `asset_id ASC`. Wired into `AssetLifecycleService.listAssets`. Unreachable in any
      deployment today — see the two blockers below.)*
- [x] Define deterministic fallback to keyword search when embeddings or the provider are
      unavailable. *(2026-09-15: no `EmbeddingProvider` bean, an embed() failure, or an empty
      semantic-candidate list all fall back to the pre-existing pure-lexical path in `listAssets`
      unchanged — this is also why the fallback has never been exercised as a true fallback yet,
      since the "enabled" branch has never had anything to fall back *from* in practice.)*
- [ ] **Blocked, not a code gap:** the Phase 3 migration (`ALTER TABLE asset_search_documents ADD
      COLUMN embedding vector(1024)`, `CREATE EXTENSION vector`) is deliberately not written yet.
      This user's local PostgreSQL is a native Windows service (`postgresql-x64-18`), not the
      `infra/selfservice_db/compose.yaml` Docker container, so "swap the local image" does not apply
      — pgvector must be installed as a native extension first, or the migration breaks every clean
      boot/`mvnw test` run the moment it's added. Add the migration once that installation is
      confirmed. See docs/specs/asset-hub.md's "Semantic search embeddings: Voyage AI".
- [ ] **Blocked, not a code gap:** no Voyage AI API key exists yet
      (`asset-hub.embedding.api-key`), so `VoyageEmbeddingProvider` has never made a real call and
      cannot be live-verified the way Gemini was once ADC access was confirmed.
- [x] Return a fresh opaque `searchSessionId` only for a submitted query; do not put query text into
      that identifier or persist raw query text.
- [x] Reindex only when the approved revision or embedding model/input checksum changes. *(every
      approval reindexes, which is exactly when the approved revision changes; no separate
      change-detection needed since there's no other write path to the search document.)*
- [x] Remove/archive search documents when an asset is archived.
- [ ] Test authorization filters before ranking, pagination stability, no-result behavior, lexical
      fallback, and exclusion of unapproved text. **Deliberately out of scope, confirmed with the
      user 2026-09-15 (same DB-backed-testing decision as §2): the ranking/filter/pagination logic
      lives entirely in `AssetSearchRepository`'s native SQL, which Mockito cannot exercise — only
      a real Postgres can prove it correct. Ranking, filters, and no-result behavior were exercised
      live via curl, not exhaustively.**
- [ ] Add ranking golden tests covering lexical-only, semantic-only, overlap, equal fused scores,
      filters, and stable UUID tie-breaking. **Deliberately out of scope, same reason — native SQL
      correctness needs a real Postgres.** *(semantic-only/overlap/fused-score cases are additionally
      N/A until the Phase 3 migration lands.)*

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
- [x] Protect prompts against instructions contained in user-entered catalog text and give the AI
      path no tools or side effects. *(2026-09-15: the task instruction now lives in Vertex's
      `systemInstruction` field; the `contents` data turn carries only the canonical field JSON,
      labeled as untrusted data. Catalog text has no elevated channel to reach regardless of its
      contents. The Gemini call still declares no tools. No automated test for adversarial input yet
      — that's Phase 2 test work, tracked separately.)*
- [x] Leave template-validation interfaces unimplemented or feature-disabled until templates and
      rubrics are supplied.
- [x] Test timeout, provider error, malformed output, duplicate tags, oversized input, and stale
      suggestions after an edit. *(2026-09-15: `GeminiAssetAiProviderTest` uses
      `RestClient.builder()` bound to `MockRestServiceServer` for malformed response, empty
      candidates, non-2xx (`PROVIDER_ERROR`), connection failure (`PROVIDER_TIMEOUT`), and asserts
      `sourceUrl` never appears in the outgoing body and the instruction lives in
      `systemInstruction`, not the data turn. `AssetAiSuggestionServiceTest` covers the disabled
      path, checksum-based reuse, provider-exception → `FAILED`, unexpected-`RuntimeException` →
      `FAILED`, tag normalization/truncation (case-sensitive dedup, not case-insensitive — a real
      behavior this test caught), and submitter/owner/reviewer visibility rules. No live
      end-to-end suggestion run was exercised for the 2026-09-15 `systemInstruction` change
      specifically — only unit-level coverage.)*

## 9. Feedback and metrics

Feedback and event storage landed in Phase 1. Metrics aggregation (successful-search rate,
time-to-useful-result, contributor adoption, review turnaround) was added 2026-09-15 — see below.

- [x] Add general asset feedback distinct from reviewer feedback.
- [x] Define privacy-minimized events for search session, detail view, source open, and POC launch.
      *(`GET /assets` records `SEARCH` without raw query text; the returned session id correlates
      subsequent detail/source/launch events.)*
- [x] Do not add reuse events or infer that a source open means reuse.
- [x] Define successful-search and time-to-useful-result calculations in the living spec before
      exposing dashboard numbers. *(2026-09-15: defined in docs/specs/asset-hub.md's new Metrics
      Contract section — successful-search rate and time-to-useful-result median/p90 — and exposed
      via `GET /asset-hub/metrics` (`AssetMetricsService`, `AssetEventRepository.findSearchSuccessStats`).)*
- [x] Add contributor-adoption and review-turnaround queries with median/p90 behavior documented.
      *(2026-09-15: `AssetRepository.countDistinctSubmittersSince` +
      `UserRepository.countByAccountTypeAndStatus` for contributor adoption over a rolling 90-day
      window; `AssetReviewRepository.findReviewTurnaroundSecondsStats` for review-turnaround
      median/p90. All exposed via `AssetMetricsService`/`GET /asset-hub/metrics`.)*
- [x] Set query-text retention/redaction policy before persisting raw search text. *(2026-09-15:
      formally recorded in the Metrics Contract — no raw query text is or has ever been persisted;
      this was already true, now it's documented as policy rather than left open.)*
- [ ] Test event ownership, accepted event types, deduplication/session behavior, and forbidden
      external submissions. *(2026-09-15: `AssetMetricsServiceTest` covers the metrics computed
      from events (successful-search rate, time-to-useful-result, contributor adoption, review
      turnaround) and `AssetControllerTest`/`AssetLifecycleServiceTest` cover `recordEvent`'s
      basic save path and the generic non-internal `403`. Not done: event-type acceptance
      validation, deduplication/session-correlation behavior, and forbidden-external-submission
      tests specifically for `POST /assets/{assetId}/events`.)*

## 10. Verification and rollout

- [x] Add service tests for every state transition and authorization invariant. *(2026-09-15:
      `AssetLifecycleServiceTest` (12 methods), `AssetReviewServiceTest`, `AssetAiSuggestionServiceTest`,
      `EmployeeRoleServiceTest`, `AssetMetricsServiceTest`, and `CurrentUserTest` now cover this —
      see §3–§9 above for specifics. Database-backed concurrency/races remain out of scope, see below.)*
- [x] Add controller slice tests implementing the generated API interface. *(2026-09-15:
      `AssetControllerTest`, `AssetReviewControllerTest`, `EmployeeControllerTest` — `@WebMvcTest` +
      MockMvc, matching `PocFilesControllerTest`'s established pattern.)*
- [ ] Add database-backed tests for revision promotion, role audit, search indexing, and races.
      **Deliberately out of scope, same DB-backed-testing decision as §2/§7.** *(role audit,
      revision-promotion, and search-indexing all have unit-level coverage via mocked repositories;
      only genuine transactional/concurrency behavior needs a real Postgres.)*
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

Deferred items (as of 2026-09-15):
  - §6 partial — launch-token minting still goes entirely through the existing
    /pocs/{slug}/launch endpoint; Asset Hub only derives the read-only `launchable` flag.
  - §7 semantic search is now scaffolded (EmbeddingProvider/VoyageEmbeddingProvider, RRF merge in
    AssetSearchRankingService) but blocked from real use on two external prerequisites, not code:
    native pgvector installation on this user's local PostgreSQL, and a Voyage AI API key. Neither
    exists yet — see §7's checklist items above and docs/specs/asset-hub.md.
  - §8 real AI provider integration is done (Gemini on Vertex AI, `1229f4e`), including
    prompt-injection hardening via `systemInstruction` separation (2026-09-15). No automated test
    for adversarial input yet.
  - §9 metrics aggregation is done (`AssetMetricsService`, `GET /asset-hub/metrics`, 2026-09-15):
    successful-search rate, time-to-useful-result, contributor adoption, review turnaround.
  - §10's database-backed concurrency and PostgreSQL integration coverage remains deferred; unit
    coverage now protects managed-role policy/auditing, source URL validation, and search events.
    A dedicated test-coverage pass landed immediately after this one (same day) — see the update
    below.

Known risks:
  - Database-backed edge coverage is still needed for concurrent reviewers, repeated decisions,
    every illegal state transition, POC-link edge cases, and pessimistic role-update locking.
  - This sandbox's own embedded Tomcat could not stay up for verification (loopback-socket
    restriction) — all live verification ran against the user's own locally-run process, restarted
    several times over the course of this session.
  - `VoyageEmbeddingProvider` and the semantic branch of `AssetLifecycleService.listAssets` have
    never executed against a real Voyage API or a Postgres with pgvector — they are compiled and
    reasoned about, not live-verified, unlike every other provider integration in this feature.
```

**Update 2026-09-15 — test-coverage pass.** Closed most of §3–§10's open test checkboxes, following
this repo's two existing conventions exactly (no new testing paradigm introduced): plain-Mockito
service tests and `@WebMvcTest`+MockMvc controller slices with a manually-planted
`JwtAuthenticationToken`.

```text
New test files:
  security/CurrentUserTest.java
  asset/controller/AssetControllerTest.java
  asset/controller/AssetReviewControllerTest.java
  asset/controller/EmployeeControllerTest.java
  asset/service/AssetReviewServiceTest.java
  asset/service/AssetAiSuggestionServiceTest.java
  asset/ai/GeminiAssetAiProviderTest.java
  asset/search/AssetSearchIndexerTest.java
  asset/service/AssetMetricsServiceTest.java

Expanded:
  asset/service/AssetLifecycleServiceTest.java   (2 tests / 2 of 12 methods -> 12 tests / all 12 methods)
  asset/service/EmployeeRoleServiceTest.java     (added listEmployees coverage)

Tests run:
  .\mvnw.cmd test   — 549 tests, 0 failures, 0 errors relative to baseline (the 2 pre-existing
                      ScratchRedirectUriProbe errors remain — confirmed unrelated, see above)
                      (466 -> 549, +83 new cases)

Result: pass. One real behavior was caught and corrected by writing the test, not found by
  mvn compile: AssetAiSuggestionService's tag normalization dedups by exact string equality
  (.distinct()), not case-insensitively — "Tag One" and "tag one" both survive. The test now
  documents this as intended behavior rather than asserting the wrong expectation.

Deferred items (unchanged from the reasons already recorded per-section above):
  - §2/§7/§10 database-backed tests (migration/repository integration, ranking-SQL correctness,
    revision-promotion/role-lock concurrency races) — confirmed with the user: stay 100%
    Mockito-only, do not introduce Testcontainers/a DB-backed convention for just this feature.
  - §6 POC-association edge cases (unhosted links, deploying/failed/hidden/deleted POCs) — no new
    tests added this pass.
  - §9 event-type acceptance validation, dedup/session-correlation, and forbidden-external-
    submission tests for POST /assets/{assetId}/events specifically — not added this pass (the
    metrics *computed from* events are tested; the event-acceptance endpoint itself is not).
  - §10 AI/embedding configuration validation (invalid config should fail startup) — not added.
  - Portal component-level tests (self-service-portal) — see that repo's own checklist.

Known risks (in addition to the ones already listed above):
  - AssetSearchRankingService's RRF merge and AssetMetricsService's four formulas are unit-tested
    against constructed fixtures only; neither has ever run against a real Postgres, so a mismatch
    between the native SQL's actual column types/behavior and the Java-side assumptions (e.g.
    Instant vs Timestamp conversion in the tie-break query) would not be caught by this suite.
```

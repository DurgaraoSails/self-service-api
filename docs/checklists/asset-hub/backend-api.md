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

- [ ] Amend `docs/specs/asset-hub.md` when implementation discovers a changed decision; record the
      reason in its changelog.
- [ ] Implement every method/path in the feature spec's API table exactly; update the spec first if
      a generated-interface limitation requires a path or shape change.
- [ ] Add `openapi/components/schemas/asset.yaml` with closed enums for asset type, revision state,
      review decision, AI job state, and feedback shape.
- [ ] Add request/response schemas for asset summaries, approved detail, owner-authorized working
      revision, create/update/submit, reviewer queue, review decision, facets, and paginated search.
- [ ] Model revision identifiers and an `expectedVersion` concurrency value on every
      mutation that can race.
- [ ] Add optional POC launch-action data without exposing a second editable launch URL.
- [ ] Add employee listing and managed-role request/response schemas to the user contract.
- [ ] Restrict managed role values in the schema to `ADMIN` and `ASSET_REVIEWER`; do not include
      `USER` or `SUPERADMIN`.
- [ ] Define consistent `400`, `401`, `403`, `404`, `409`, and AI-unavailable responses using the
      existing common response components.
- [ ] Run OpenAPI generation and inspect the generated interfaces/models before implementation.
- [ ] Commit Phase 0 contract changes before either owner builds against generated models.

## 2. Database and entities

- [ ] Use the next contiguous Flyway versions available after rebasing. Keep one create-table per
      migration plus a separate extension migration; do not renumber an already-applied migration.
- [ ] Add Flyway migrations for `assets`, `asset_revisions`, `tags`,
      `asset_revision_tags`, `asset_reviews`, `asset_ai_suggestions`,
      `asset_search_documents`, `asset_feedback`, `asset_events`, and `role_change_audit`.
      Create `asset_search_documents` with its base columns, `search_text`, stored `tsvector`, and
      provider/model/dimensions/checksum columns nullable in this migration — keyword search must
      exist for the Phase 1 vertical slice. Add the `vector` embedding column through a separate,
      later migration in Phase 3 (see the last bullet in this section).
- [ ] Use UUIDs for asset and revision identifiers exposed through URLs.
- [ ] Store stable identity and `approved_revision_id`/`working_revision_id` on `assets`, with
      constraints preventing cross-asset revision pointers.
- [ ] Add an optional FK from `assets.poc_id` to `pocs.id`.
- [ ] Enforce one revision number per asset and immutable approved revision content.
- [ ] Add indexes for owner, submitter, type, review state, approved revision, POC link, created/
      submitted dates, normalized tag name, and reviewer queue ordering.
- [ ] Add a GIN full-text index over approved search documents.
- [ ] Enable/model `pgvector` only after confirming the target Cloud SQL/local PostgreSQL versions
      and migration behavior; record embedding dimensions explicitly.
- [ ] Keep template and template-validation tables deferred until their fields/rubrics are supplied.
- [ ] Add JPA entities/repositories without bidirectional collections that make revision boundaries
      ambiguous.
- [ ] Add migration and repository integration tests, including constraint failures.
- [ ] Add the two same-asset composite revision-pointer FKs only after `asset_revisions` exists and
      verify cross-asset pointers fail at the database layer.
- [ ] Keep only the `vector` embedding column and the pgvector extension itself in a Phase 3
      migration (an `ALTER TABLE asset_search_documents ADD COLUMN embedding vector(...)` plus
      `CREATE EXTENSION vector`), added once the local PostgreSQL image is swapped for a
      pgvector-enabled one. The rest of `asset_search_documents` (keyword/tsvector columns) is a
      Phase 0/1 migration so the core vertical slice ships against the existing local PostgreSQL
      image with working keyword search.

## 3. Internal-account authorization

- [ ] Add a reusable current-user/internal-account authorization helper rather than duplicating JWT
      claim expressions across controllers.
- [ ] Require `INTERNAL` on every asset, review, feedback, search, telemetry, employee-list, and
      Asset Hub role-management operation.
- [ ] Require `ASSET_REVIEWER` in addition to `INTERNAL` for reviewer queue and decision operations.
- [ ] Ensure `ADMIN` alone does not grant Asset Hub review access.
- [ ] Ensure `SUPERADMIN` alone does not grant Asset Hub review access.
- [ ] Add controller/security tests for external, unauthenticated, internal employee, reviewer,
      admin, and superadmin combinations.

## 4. Multi-role management

- [ ] Add an internal-employee listing/filter suitable for superadmin role administration; do not
      force the portal to use the customer/trial-oriented presentation.
- [ ] Add one atomic managed-role replacement operation for an internal target.
- [ ] Require the caller to be both `INTERNAL` and `SUPERADMIN`.
- [ ] Preserve the target's baseline `USER` role and any database-managed `SUPERADMIN` role.
- [ ] Reject unknown roles, duplicates after normalization, external targets, inactive policy
      violations, and self-modification.
- [ ] Decide and document compatibility for the existing promote/demote ADMIN endpoints; do not let
      them become a bypass around the internal-target policy for Asset Hub role administration.
- [ ] Write `role_change_audit` in the same transaction as the role update.
- [ ] Return the authoritative updated role list.
- [ ] Document that the current JWT carries role claims and when an updated assignment becomes
      effective; add immediate revocation only if separately approved.
- [ ] Test assigning `ADMIN` and `ASSET_REVIEWER` together, removing one role, idempotent replacement,
      forbidden targets, and attempts to assign or remove `SUPERADMIN`.
- [ ] Lock the target user row during replacement so concurrent requests cannot lose a role update
      or write a mismatched audit record.

## 5. Asset lifecycle and revisions

- [ ] Implement draft creation with the authenticated employee as submitter and initial revision
      author.
- [ ] Support an explicit owner separate from the submitter, restricted to eligible internal users.
- [ ] Validate asset type, bounded catalog fields, normalized tags, and absolute HTTP(S) source URL.
- [ ] Never dereference, preview, or validate source content server-side.
- [ ] Implement working-revision updates with optimistic concurrency.
- [ ] Submit an immutable revision into `PENDING_REVIEW`.
- [ ] Keep the last approved revision pointer unchanged while another revision is draft,
      changes-requested, or pending.
- [ ] Append review decisions rather than updating historical decisions.
- [ ] Enforce no self-review when reviewer equals asset submitter or revision author.
- [ ] Implement transitions for approve, reject, and request changes and reject illegal transitions
      with `409`.
- [ ] Lock the target revision/asset during a decision, then re-read state and self-review identity
      before writing the append-only review.
- [ ] Promote an approved working revision and its search-index work atomically or through a durable
      post-commit job with observable retry state.
- [ ] Define archive behavior without hard deletion and ensure archived assets leave discovery.
- [ ] Test the entire state machine, stale writes, repeated decisions, and concurrent reviewers.

## 6. POC association and launch

- [ ] Allow `poc_id` only for `POC` assets; reject it for other asset types.
- [ ] Allow only an internal `ADMIN` to create/change the POC association and prevent linking
      deleted records.
- [ ] Derive launch availability from the existing POC readiness/active-version data.
- [ ] Return a launch action only for a ready POC; do not duplicate `appUrl` on the asset.
- [ ] Reuse the existing launch/workspace API semantics and employee trial exemption.
- [ ] Test unlinked POC assets, unhosted links, deploying/failed POCs, hidden/deleted POCs, and ready
      POCs.

## 7. Discovery and hybrid search

- [ ] Build the search document only from approved catalog fields and accepted tags.
- [ ] Exclude source contents, working revisions, rejected revisions, review feedback, and unaccepted
      AI suggestions.
- [ ] Implement keyword search with deterministic ordering and pagination.
- [ ] Implement filters/facets for type, owner, tags, and launchable POC.
- [ ] Define an `EmbeddingProvider` interface and store model, dimensions, input checksum, and
      generation timestamp.
- [ ] Add semantic retrieval over approved search documents and combine it with lexical ranking
      using the exact reciprocal-rank-fusion algorithm in the feature spec.
- [ ] Define deterministic fallback to keyword search when embeddings or the provider are
      unavailable.
- [ ] Return a fresh opaque `searchSessionId` only for a submitted query; do not put query text into
      that identifier or persist raw query text.
- [ ] Reindex only when the approved revision or embedding model/input checksum changes.
- [ ] Remove/archive search documents when an asset is archived.
- [ ] Test authorization filters before ranking, pagination stability, no-result behavior, lexical
      fallback, and exclusion of unapproved text.
- [ ] Add ranking golden tests covering lexical-only, semantic-only, overlap, equal fused scores,
      filters, and stable UUID tie-breaking.

## 8. AI metadata suggestions

- [ ] Define an AI provider boundary separate from search embeddings.
- [ ] Send only bounded, user-entered catalog fields; never send or fetch source URL content.
- [ ] Require schema-validated structured suggestions for title, summary, and tags.
- [ ] Store suggestions separately from revision metadata until explicitly accepted.
- [ ] Record status, safe error classification, model/schema version, input checksum, and timestamps.
- [ ] Ensure AI failure, timeout, or malformed output cannot block saving, submitting, or reviewing.
- [ ] Protect prompts against instructions contained in user-entered catalog text and give the AI
      path no tools or side effects.
- [ ] Leave template-validation interfaces unimplemented or feature-disabled until templates and
      rubrics are supplied.
- [ ] Test timeout, provider error, malformed output, duplicate tags, oversized input, and stale
      suggestions after an edit.

## 9. Feedback and metrics

- [ ] Add general asset feedback distinct from reviewer feedback.
- [ ] Define privacy-minimized events for search session, detail view, source open, and POC launch.
- [ ] Do not add reuse events or infer that a source open means reuse.
- [ ] Define successful-search and time-to-useful-result calculations in the living spec before
      exposing dashboard numbers.
- [ ] Add contributor-adoption and review-turnaround queries with median/p90 behavior documented.
- [ ] Set query-text retention/redaction policy before persisting raw search text.
- [ ] Test event ownership, accepted event types, deduplication/session behavior, and forbidden
      external submissions.

## 10. Verification and rollout

- [ ] Add service tests for every state transition and authorization invariant.
- [ ] Add controller slice tests implementing the generated API interface.
- [ ] Add database-backed tests for revision promotion, role audit, search indexing, and races.
- [ ] Add configuration validation and safe disabled fallbacks for AI/embedding providers.
- [ ] Verify existing authentication, POC, user administration, activity, and file tests do not
      regress.
- [ ] Run the full Maven test suite and OpenAPI generation from a clean checkout.
- [ ] Update this checklist and the feature changelog with any deliberate deferrals.
- [ ] Confirm `git diff --check` and `git status --short` show no accidental generated, secret,
      target, or unrelated files.

## Handoff evidence

Fill this in before declaring the backend work complete:

```text
Implementation commit(s):
Tests run:
Result:
Deferred items:
Known risks:
```

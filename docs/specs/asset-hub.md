# Asset Hub

## Status

Draft — implementation ready for the POC described here

## Overview / Purpose

The Asset Hub is an employee-only catalog for finding and governing internal AI work. Assets are
currently scattered across SharePoint, repositories, and other source systems, which makes them
hard to discover and leaves ownership and review status unclear.

An employee submits catalog metadata and a source URL. AI may suggest metadata from the fields the
employee entered, but a human reviewer makes the publication decision. Approved assets are
discoverable through keyword and semantic search. The Hub never fetches or serves SharePoint
content in this phase; opening the stored URL returns the employee to SharePoint, which remains the
source of truth and enforces its own permissions.

The initial asset types are:

- `AI_USE_CASE`
- `POC`
- `BLOG`
- `ARTICLE`
- `HACKATHON_IDEA`
- `DOCUMENT`

## Explicit Non-goals

- No Microsoft Graph API, SharePoint application registration, SharePoint synchronization, source
  permission replication, or source-content extraction.
- No uploads, previews, copied binaries, source proxy, or URL crawler.
- No reuse workflow, reuse count, reuse telemetry, or “reused” terminology.
- No automatic reviewer assignment, email/in-app notification system, weekly digest, or follower
  model.
- No BLOG/ARTICLE template fields or validation score until the templates and rubrics are supplied.
- No portal redesign or second design system.

## Requirements

### Access and roles

- Every Asset Hub API and portal route requires a verified `INTERNAL` account.
- Every internal employee can browse approved assets and create or edit their own submissions.
- `ASSET_REVIEWER` is independent of `ADMIN` and `SUPERADMIN`.
- A reviewer must have both `accountType=INTERNAL` and the `ASSET_REVIEWER` role.
- A reviewer cannot approve, reject, or request changes on an asset they submitted or a revision
  they authored. This is enforced by the API, not only by the portal.
- A superadmin does not implicitly gain review powers; a person who performs both duties holds both
  `SUPERADMIN` and `ASSET_REVIEWER`.
- `SUPERADMIN` remains database-managed. It cannot be granted or revoked through an API or portal
  control.
- An internal superadmin can atomically assign multiple allowlisted managed roles to an internal
  employee. `SUPERADMIN` is not part of that allowlist.
- Role changes are audited with actor, target, before/after roles, and timestamp.

### Catalog and sources

- An asset has an owner, submitter, type, title, description/summary, tags, source URL, review
  status, and revision history.
- Source URLs are stored only. The backend does not crawl, download, proxy, preview, or inspect
  their content.
- SharePoint integration in this phase is limited to storing a SharePoint URL. There is no
  Microsoft Graph dependency, synchronization cursor, selected-site permission, or ingestion job.
- Catalog metadata is visible to all internal Asset Hub users after approval. The submission UI
  warns contributors not to copy restricted source content into catalog fields.
- Source links open in a new browser context with safe external-link behavior. The source system
  remains responsible for access control.
- Asset Hub does not provide upload or binary-storage behavior.

### Review and revision behavior

- Revision states are `DRAFT`, `PENDING_REVIEW`, `CHANGES_REQUESTED`, `APPROVED`, `REJECTED`, and
  `SUPERSEDED`. Archival belongs to the stable asset, not a revision.
- Review decisions are append-only records associated with an exact revision.
- Submitting a draft freezes that revision for review. Further edits create or update a working
  revision rather than modifying an approved revision.
- An approved asset can have one approved revision and a different working revision.
- While a new working revision is pending, the last approved revision stays visible and searchable.
- Approving the working revision atomically promotes it to the approved revision.
- All mutation and review operations use an expected revision/version to reject stale writes.
- Only the approved revision is included in general discovery and search.

### POC assets

- `POC` is a normal Asset Hub asset type and may exist without a hosted application.
- A POC asset may reference an existing `pocs` row through an optional `poc_id`.
- Launch availability is derived from the linked POC's actual readiness; it is not stored as a
  second launch URL or manually maintained boolean on the asset.
- A launch action is returned/shown only when the linked POC is active and launchable.
- Launching reuses the existing POC launch/workspace flow and authorization.
- Review approval and POC hosting state are independent.

### AI and search

- AI metadata suggestions operate only on catalog fields entered by the employee. They never fetch
  a source URL.
- Suggested title, summary, and tags remain proposals until a contributor or reviewer accepts them.
- AI failure never prevents manual submission or review.
- Semantic embeddings are generated only from approved catalog metadata intended for all internal
  Asset Hub users.
- Search combines keyword matching, filters, and semantic similarity over the approved revision.
- Search supports filters for asset type, tags, owner, and launchable POCs.
- Search does not expose unapproved revisions, AI proposals, review feedback, or source content.

### Blog/article template validation

- Template validation is deferred until the BLOG and ARTICLE templates and scoring rubrics are
  provided.
- The first implementation leaves an extension seam for versioned templates, deterministic required
  field checks, and AI rubric results.
- A future validation score is advisory and cannot approve or reject an asset automatically.
- Future validation operates on user-entered template fields, not the content behind a source URL.

### Feedback and measurement

- Internal employees can leave asset feedback. Feedback is separate from review feedback.
- The POC has no reuse action, reuse metric, reuse button, or reuse event.
- Measurement covers search-to-detail/source-open time, successful-search rate, source opens, POC
  launches, contributor adoption, and review turnaround time.

## Architecture Decisions

### Metadata catalog, not a content repository

Keeping only catalog metadata and source URLs makes access ownership unambiguous and avoids a
second copy of SharePoint content. This also removes Graph permissions, item-level ACL replication,
and source ingestion from the POC. The tradeoff is intentional: AI and semantic search can use only
the metadata employees explicitly enter into the Hub.

### Stable asset plus immutable revisions

`assets` owns stable identity and pointers to the current working and approved revisions.
`asset_revisions` owns the reviewable fields and status. This allows an approved version to remain
available while a correction is reviewed, gives review decisions an exact immutable target, and
prevents search results changing before approval.

### Managed privileged roles

`users.roles` remains the existing `TEXT[]` and JWT claim. A new multi-role operation manages only
an explicit privileged-role allowlist (initially `ADMIN` and `ASSET_REVIEWER`) while preserving
baseline `USER` and database-managed `SUPERADMIN`. Both caller and target must be internal for this
operation. Role changes take effect in newly issued/refreshed portal tokens under the current JWT
architecture.

### PostgreSQL-backed hybrid search for the POC

The existing PostgreSQL database stores approved search documents. PostgreSQL full-text search
provides lexical retrieval and `pgvector` provides semantic retrieval without adding a second
search service. The local PostgreSQL container must use a PostgreSQL 17 image containing pgvector
before the vector migration is applied. Provider-specific embedding generation stays behind an
application interface and records the provider, model, dimensions, input checksum, and generated
timestamp. The provider/model and therefore the fixed vector dimension must be recorded in this
spec before the semantic-search migration is committed; this is the only unresolved decision that
blocks the intelligence gate, not the core catalog/review vertical slice.

## State Transition Contract

The service layer is the only place allowed to transition revisions. Controllers map generated
models and delegate; repositories must not expose ad-hoc state-changing queries to controllers.

| Current state | Command | Next state | Required caller | Notes |
| --- | --- | --- | --- | --- |
| no asset | create | `DRAFT` revision 1 | internal employee | Caller becomes submitter and revision author. |
| `DRAFT` | update | `DRAFT` | submitter or owner | Requires matching `expectedVersion`. |
| `DRAFT` | submit | `PENDING_REVIEW` | submitter or owner | Freezes the revision. |
| `PENDING_REVIEW` | approve | `APPROVED` | different internal reviewer | Previous approved revision becomes `SUPERSEDED`; pointers and search work change atomically. |
| `PENDING_REVIEW` | request changes | `CHANGES_REQUESTED` | different internal reviewer | Feedback is required. |
| `PENDING_REVIEW` | reject | `REJECTED` | different internal reviewer | Feedback is required. |
| `CHANGES_REQUESTED` | create working revision | new `DRAFT` | submitter or owner | Clone fields into the next revision number; do not edit the reviewed row. |
| `REJECTED` | create working revision | new `DRAFT` | submitter or owner | Explicit retry with preserved history. |
| `APPROVED` | create working revision | new `DRAFT` | submitter or owner | Approved revision remains discoverable. |
| any non-archived asset | archive | unchanged revisions | submitter, owner, or internal admin | Sets `assets.archived_at`; removes it from discovery. |

Additional invariants:

- At most one revision per asset is `APPROVED`; the stable asset's `approved_revision_id` points to
  it.
- At most one revision per asset is the active working revision; `working_revision_id` points to
  it while it is `DRAFT`, `PENDING_REVIEW`, `CHANGES_REQUESTED`, or `REJECTED`.
- Approved and submitted revisions are never edited in place.
- A decision is rejected if the current user matches either `assets.submitted_by_user_id` or the
  target revision's `authored_by_user_id`.
- Review authorization and the self-review check run inside the same transaction that locks the
  revision, so two reviewers cannot both decide it.
- A `CHANGES_REQUESTED` or `REJECTED` revision remains immutable; the contributor explicitly creates
  the next draft revision from it.

## Data Model

Use the next available Flyway versions at implementation time and keep one create-table migration
per table. The logical schema is fixed as follows; implementation may shorten constraint/index
names, but not weaken these invariants without updating this spec first.

### `assets`

| Column | Type | Rules |
| --- | --- | --- |
| `id` | `UUID` | PK, application-generated |
| `asset_type` | `VARCHAR(32)` | closed check using the six initial types |
| `owner_user_id` | `VARCHAR(36)` | FK `users(id)`, internal target checked by service |
| `submitted_by_user_id` | `VARCHAR(36)` | FK `users(id)`, immutable |
| `poc_id` | `UUID NULL` | FK `pocs(id)`; allowed only for `asset_type='POC'` |
| `approved_revision_id` | `UUID NULL` | same-asset FK added after `asset_revisions` exists |
| `working_revision_id` | `UUID NULL` | same-asset FK added after `asset_revisions` exists |
| `archived_at` | `TIMESTAMPTZ NULL` | null means active |
| `archived_by_user_id` | `VARCHAR(36) NULL` | FK `users(id)` |
| `version` | `BIGINT` | JPA `@Version`, default 0 |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | UTC, not null |

Use composite foreign keys `(approved_revision_id, id)` and `(working_revision_id, id)` to a unique
`asset_revisions(id, asset_id)` pair so an asset cannot point at another asset's revision.

### `asset_revisions`

| Column | Type | Rules |
| --- | --- | --- |
| `id` | `UUID` | PK |
| `asset_id` | `UUID` | FK `assets(id)`, not null |
| `revision_number` | `INTEGER` | positive; unique with `asset_id` |
| `state` | `VARCHAR(32)` | closed revision-state check |
| `title` | `VARCHAR(200)` | trimmed, not blank |
| `summary` | `TEXT` | maximum 4,000 characters at API/service boundary |
| `problem_statement` | `TEXT NULL` | maximum 8,000 characters |
| `business_impact` | `TEXT NULL` | maximum 8,000 characters |
| `solution_overview` | `TEXT NULL` | maximum 8,000 characters |
| `source_url` | `VARCHAR(2048)` | absolute HTTP(S), stored only |
| `authored_by_user_id` | `VARCHAR(36)` | FK `users(id)`, immutable |
| `submitted_at` | `TIMESTAMPTZ NULL` | set on transition to pending |
| `version` | `BIGINT` | JPA `@Version`, default 0 |
| `created_at`, `updated_at` | `TIMESTAMPTZ` | UTC, not null |

### Supporting tables

| Table | Required columns and constraints |
| --- | --- |
| `tags` | `id BIGINT`, `name VARCHAR(80)`, `normalized_name VARCHAR(80) UNIQUE`, timestamps |
| `asset_revision_tags` | `(revision_id, tag_id)` composite PK; FKs with delete cascade from revision only |
| `asset_reviews` | UUID PK, revision FK, reviewer user FK, `APPROVE|REQUEST_CHANGES|REJECT`, feedback (required and max 4,000 for non-approve), `created_at`; append-only |
| `asset_ai_suggestions` | UUID PK, revision FK, `PENDING|RUNNING|SUCCEEDED|FAILED`, suggested title/summary/tags JSONB, provider/model/schema version, SHA-256 input checksum, safe error code, timestamps |
| `asset_search_documents` | `asset_id` PK/FK, unique approved `revision_id` FK, `search_text`, stored `tsvector`, vector embedding, provider/model/dimensions/checksum, `indexed_at` |
| `asset_feedback` | UUID PK, asset/user FKs, `HELPFUL|NOT_HELPFUL`, optional comment max 2,000, `created_at`; one current entry per `(asset_id,user_id)` or an explicitly documented append-only alternative |
| `asset_events` | UUID PK, optional asset/user FKs, `SEARCH|DETAIL_VIEW|SOURCE_OPEN|POC_LAUNCH`, optional `search_session_id UUID`, `occurred_at`; no reuse event and no raw query text |
| `role_change_audit` | UUID PK, actor/target user FKs, `before_roles TEXT[]`, `after_roles TEXT[]`, `created_at`; append-only |

Deferred tables are `asset_templates` and `asset_template_validations`; do not create placeholder
tables until the templates and rubric are supplied.

## API Surface

All paths inherit the API's bearer-token security. Asset paths additionally enforce `INTERNAL`.
Pagination uses zero-based `page`, bounded `size` (default 20, maximum 100), and stable secondary
ordering by UUID after the documented primary sort.

| Method and path | Authorization | Request/result |
| --- | --- | --- |
| `GET /assets` | internal | Approved search. Query: `q`, repeated `type`, repeated `tag`, `ownerId`, `launchable`, `page`, `size`. Returns `AssetPageResponse` plus a generated `searchSessionId` when `q` is present. |
| `GET /assets/facets` | internal | Counts for approved types/tags/owners and launchable POCs under the same optional query filters. |
| `GET /assets/{assetId}` | internal | Approved `AssetDetailResponse`; `404` when no approved revision or archived, except authorized editor/reviewer endpoints below. |
| `POST /assets` | internal | `CreateAssetRequest`; creates asset + revision 1 draft; returns `201 AssetEditorResponse`. |
| `GET /assets/mine` | internal | Caller-owned/submitted assets including working status, paginated. |
| `GET /assets/{assetId}/working-revision` | submitter, owner, or reviewer | `AssetEditorResponse` including working revision and last approved summary. |
| `POST /assets/{assetId}/working-revision` | submitter or owner | Clones approved/changes-requested/rejected revision into next draft; `409` if an editable/pending working revision already exists. |
| `PATCH /assets/{assetId}/working-revision` | submitter or owner | `UpdateAssetRevisionRequest` including `expectedVersion`; draft only. |
| `POST /assets/{assetId}/working-revision/submit` | submitter or owner | Body contains `expectedVersion`; transitions draft to pending. |
| `POST /assets/{assetId}/archive` | submitter, owner, or internal admin | Body contains asset `expectedVersion`; soft archives and returns `204`. |
| `POST /assets/{assetId}/ai-suggestions` | submitter or owner | Starts suggestions for the current draft and returns `202 AssetAiSuggestionResponse`. |
| `GET /assets/{assetId}/ai-suggestions/latest` | submitter, owner, or reviewer | Latest run for the current working revision. |
| `GET /asset-reviews` | internal reviewer | Pending queue. Query: type/age/page/size; oldest submitted first, UUID tie-break. |
| `GET /asset-reviews/{revisionId}` | internal reviewer | Exact frozen revision, last approved revision when present, and review history. |
| `POST /asset-reviews/{revisionId}/decisions` | different internal reviewer | `CreateAssetReviewRequest {decision, feedback?, expectedVersion}`; returns updated review detail. |
| `PUT /assets/{assetId}/feedback` | internal | `AssetFeedbackRequest {rating, comment?}`; idempotently replaces caller's feedback. |
| `POST /assets/{assetId}/events` | internal | `AssetEventRequest {eventType, searchSessionId?}`; only detail/source/launch types valid here. |
| `GET /employees` | internal superadmin | Internal users only. Query: search/page/size. Returns roles needed for management. |
| `PUT /employees/{userId}/roles` | different internal superadmin | `UpdateManagedRolesRequest {roles:[ADMIN|ASSET_REVIEWER]}`; replaces only managed roles and returns employee. |

`CreateAssetRequest` and `UpdateAssetRevisionRequest` use the same editable field group:

```json
{
  "assetType": "POC",
  "ownerUserId": "01J...",
  "title": "Radiology Triage Assistant",
  "summary": "Flags high-priority scans for radiologist review.",
  "problemStatement": "Urgent studies can wait in a general queue.",
  "businessImpact": "Shortens time to clinical review.",
  "solutionOverview": "A prioritization model ranks incoming studies.",
  "sourceUrl": "https://tenant.sharepoint.com/sites/ai/Shared%20Documents/radiology",
  "tags": ["Healthcare", "Computer Vision"],
  "pocId": "optional-uuid; accepted only from an internal ADMIN"
}
```

Editable-field policy is fixed:

| Field | Contributor policy |
| --- | --- |
| `title`, `summary`, `problemStatement`, `businessImpact`, `solutionOverview`, `sourceUrl`, `tags` | Submitter or owner may change on a `DRAFT`. |
| `ownerUserId` | Submitter or current owner may change on a `DRAFT`; target must be an active internal employee. |
| `assetType` | Submitter or owner may change only before the asset has ever had an approved revision. |
| `pocId` | Only an internal `ADMIN` may set/change it; asset type must be `POC` and target POC must be non-deleted. |

Regular contributors can create a `POC` asset without linking a hosted record. Changes to stable
asset fields and draft-revision fields occur in one transaction and use both asset and revision
`expectedVersion` values where both rows change.

The API never returns `appUrl` as part of an asset. `AssetDetailResponse` contains an optional
`launch { pocId, launchable }`; the portal uses its existing POC workspace route when `launchable`
is true.

## Error Contract

Use the existing API error envelope and these stable codes:

| HTTP | Code | Meaning |
| --- | --- | --- |
| 400 | `INVALID_ASSET_SOURCE_URL` | URL is not absolute HTTP(S) or exceeds bounds. |
| 400 | `INVALID_ASSET_TYPE` | Value is outside the closed enum. |
| 400 | `INVALID_MANAGED_ROLE` | Role request contains anything except the managed allowlist. |
| 403 | `INTERNAL_ACCOUNT_REQUIRED` | Authenticated account is not internal. |
| 403 | `ASSET_REVIEWER_REQUIRED` | Caller lacks the reviewer role. |
| 403 | `SELF_REVIEW_FORBIDDEN` | Reviewer submitted the asset or authored the revision. |
| 403 | `ROLE_TARGET_MUST_BE_INTERNAL` | Role target is not an internal employee. |
| 403 | `ROLE_SELF_MANAGEMENT_FORBIDDEN` | Superadmin targeted their own role assignment. |
| 404 | `ASSET_NOT_FOUND` | Asset is absent or not visible in this context. |
| 404 | `ASSET_REVISION_NOT_FOUND` | Revision is absent or belongs to another asset/context. |
| 409 | `ASSET_REVISION_STALE` | Expected optimistic version does not match. |
| 409 | `INVALID_ASSET_TRANSITION` | Command is illegal for the current state. |
| 409 | `WORKING_REVISION_EXISTS` | Caller tried to create a second working revision. |
| 409 | `INVALID_POC_ASSOCIATION` | POC link violates type, existence, deletion, or caller policy. |
| 503 | `ASSET_AI_UNAVAILABLE` | Suggestion provider is disabled/unavailable; manual work remains usable. |

## Search Contract

Filtering happens before ranking. An empty `q` returns approved assets ordered by approved revision
`updated_at DESC, asset_id ASC`. A non-empty query follows this deterministic pipeline:

1. Normalize surrounding whitespace and reject more than 500 characters.
2. Build up to 100 lexical candidates with PostgreSQL `websearch_to_tsquery('english', :q)` and
   `ts_rank_cd`.
3. When semantic search is enabled and embedding generation succeeds, build up to 100 vector
   candidates using cosine distance.
4. Merge candidates with reciprocal-rank fusion using `1 / (60 + lexicalRank) +
   1 / (60 + semanticRank)`. A missing rank contributes zero.
5. Sort by fused score descending, approved revision `updated_at DESC`, then `asset_id ASC`.
6. Apply requested page/size to the merged ordered IDs and hydrate summaries without reordering.

The stored lexical document weights title and normalized tags as `A`, summary as `B`, problem/
impact/solution as `C`, and owner display name as `D`. It excludes source URL, review feedback,
AI proposals, and every non-approved revision. Keyword-only fallback uses the same lexical order and
response shape.

Every non-empty search response carries a random opaque `searchSessionId`. The POC does not persist
raw query text. Subsequent detail/source/launch events may carry the opaque ID for funnel metrics.

## AI Suggestion Contract

Suggestion input is a canonical JSON object containing only `assetType`, `title`, `summary`,
`problemStatement`, `businessImpact`, `solutionOverview`, and normalized tags. It excludes
`sourceUrl`, POC runtime data, user profile data, review feedback, and prior AI output. Hash the
canonical UTF-8 JSON with SHA-256; one successful result per `(revision_id, input_checksum,
provider, model, schema_version)` is reusable.

The provider returns schema-validated JSON with optional `suggestedTitle`, `suggestedSummary`, and
at most 10 `suggestedTags`. Suggested tags use the same length/normalization rules as human tags.
The API stores proposals separately. Applying selected suggestions is an ordinary draft PATCH from
the portal; there is no privileged “AI apply” mutation and no automatic submission.

If a draft changes after a run starts, the result remains stored for audit but is marked stale by
checksum comparison and cannot be presented as current. Timeouts, provider errors, and invalid JSON
set the run to `FAILED` with a safe error code and never alter the revision.

## Implementation Layout

Backend production code belongs under `com.sails.ai.selfserviceapi.asset`:

```text
asset/
  ai/            AssetAiProvider, suggestion orchestration and adapters
  config/        AssetHubProperties, search/AI beans
  controller/    generated-interface implementations only
  entity/        Asset, AssetRevision, AssetReview, Tag, search/feedback/event entities
  exception/     stable ApiException mappings
  repository/    JPA repositories and native hybrid-search repository
  search/        search document builder, EmbeddingProvider, ranking
  service/       authorization-aware lifecycle/review/query services and response mappers
```

Role management changes remain in the existing `user` package. OpenAPI asset schemas live in
`openapi/components/schemas/asset.yaml`; paths stay in `openapi/self-service-api.yaml`. Tests mirror
the production package under `src/test/java`.

Portal code belongs in `self-service-portal`:

```text
src/app/core/asset/                 typed models, API services and guards
src/app/components/asset-hub/      approved discovery/search
src/app/components/asset-detail/   approved detail/source/launch
src/app/components/asset-editor/   create/edit/AI suggestions
src/app/components/my-assets/      contributor workflow
src/app/components/asset-reviews/  reviewer queue/detail
src/app/components/employee-roles/ superadmin multi-role management
```

Names may be adjusted to existing portal naming conventions, but responsibilities must remain
separate and components must stay focused.

## Configuration and Safe Defaults

- `ASSET_HUB_ENABLED=false` by default until migrations and both applications are deployed.
- `ASSET_AI_ENABLED=false` and `ASSET_SEMANTIC_SEARCH_ENABLED=false` independently control optional
  intelligence; keyword search and manual review must work when both are false.
- Provider secrets come from the existing deployment secret mechanism and are never committed or
  logged.
- AI timeouts, maximum input length, model name, and embedding dimensions bind through validated
  `AssetHubProperties`; invalid enabled configuration fails startup.
- Do not add any Microsoft/SharePoint credential or Graph dependency.
- Local database setup must match production extensions before semantic search tests are enabled.

## Definition of Done

The POC is implementation-complete when all non-deferred boxes in both checklist files and the
cross-workstream acceptance list pass, and all of the following are true:

- The complete create → submit → independent review → approved discovery flow works with AI and
  semantic-search flags disabled.
- External accounts receive `403` at the API and cannot activate portal routes.
- Approved content remains stable while a new revision moves through review.
- Source URLs are never fetched by backend code or AI input construction.
- POC launch is derived from the existing linked POC and never from an asset-owned runtime URL.
- Role management changes only `ADMIN`/`ASSET_REVIEWER`, preserves `USER`/`SUPERADMIN`, targets an
  internal employee, and writes an audit row in the same transaction.
- Keyword search is deterministic; semantic search follows the ranking contract when enabled and
  falls back without changing authorization or response shape.
- The portal uses the existing design system, passes its tests/build and AXE/WCAG checks, and has
  complete loading/empty/error/keyboard states.
- Maven tests and OpenAPI generation pass from a clean checkout.
- No Graph dependency, template-score placeholder table, reuse behavior, or mockup-only feature is
  present.

## Security Considerations

- Protect Asset Hub APIs and portal routes with an internal-account guard; navigation hiding is not
  enforcement.
- Combine `INTERNAL` and `ASSET_REVIEWER` checks for every review operation.
- Perform the self-review check in the service transaction immediately before recording a decision.
- Validate managed role values against a closed allowlist and exclude `SUPERADMIN` from request
  schemas.
- Do not let role management remove a target's baseline `USER` role or alter an existing
  `SUPERADMIN` role.
- Validate source URLs as absolute HTTP(S) URLs. Never dereference them server-side.
- Treat catalog text as untrusted input for rendering and AI prompts.
- AI calls receive only bounded catalog fields and must return schema-validated structured output.
- Search and detail responses must not include working revisions or review feedback unless the
  caller is explicitly authorized to see them.
- Avoid storing raw search queries when they may contain sensitive text; define retention and
  normalization before enabling query telemetry.

## Portal Design Direction

The supplied `ai_asset_hub_mockup.html` is an interaction reference, not a specification or visual
system. Useful patterns are the search-first discovery page, asset cards, adjacent AI suggestions,
visible workflow state, and reviewer queue.

The implementation must use the self-service portal's existing `AppShell`, navigation, theme
tokens, dark mode, `.sui-*` components, shared Angular components, responsive conventions, and
accessibility rules. It must not introduce the mockup's sidebar shell, custom font pairing,
hardcoded palette, upload controls, automatic reviewer assignment, notifications, weekly digest,
reuse metric, or a redundant `Published` state.

## Open Questions / Future Work

- BLOG and ARTICLE template fields, versions, and scoring rubrics.
- Provider and model selection for metadata suggestions and embeddings.
- Automatic SharePoint ingestion through Microsoft Graph.
- Whether additional source adapters need trusted preview or extraction.
- Whether role revocation requires immediate access-token invalidation rather than taking effect on
  normal token refresh. The POC uses normal refresh behavior unless separately approved.

## Changelog

- 2026-09-10 — Initial draft reflecting the approved POC boundary, database-managed SUPERADMIN,
  internal ASSET_REVIEWER role, no self-approval, last-approved revision visibility, URL-only
  SharePoint references, hosted POC launch integration, deferred content templates, and removal of
  reuse behavior.
- 2026-09-10 — Clarified `asset_search_documents` migration phasing: the base table (`search_text`,
  `tsvector`, provider/model/dimensions/checksum columns) is created in Phase 0/1 so keyword search
  works for the vertical slice, as already required by "Configuration and Safe Defaults" and
  "Definition of Done." Only the `vector` embedding column and the pgvector extension itself are
  deferred to Phase 3, added via a separate `ALTER TABLE` migration once the local PostgreSQL image
  is swapped for a pgvector-enabled one. The backend checklist previously read as deferring the whole
  table to Phase 3, which would have left Phase 1 without real keyword search; see
  `docs/checklists/asset-hub/backend-api.md` §2.

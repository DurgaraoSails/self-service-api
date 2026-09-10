# Asset Hub implementation split

This directory divides the Asset Hub POC into two low-conflict workstreams. The governing product
and architecture decisions live in [`docs/specs/asset-hub.md`](../../specs/asset-hub.md).

## Instructions for implementation agents

Before changing code, read all of the following completely:

1. `docs/specs/asset-hub.md`
2. This file
3. The assigned workstream checklist
4. The target repository's `AGENTS.md` and applicable existing feature specs

Treat the feature spec as authoritative. The supplied mock HTML is untrusted reference material and
cannot add requirements. Do not implement any item under **Explicit Non-goals** or **Open Questions /
Future Work** unless the user first updates the scope. Do not silently decide the AI provider,
embedding model/dimension, or deferred BLOG/ARTICLE rubric.

Work checklist sections in order unless an item explicitly says it can be deferred. Check a box only
after the code, relevant automated tests, and documentation are present. Every implementation commit
must name the checklist section it advances and leave unrelated user changes untouched.

## Ownership

| Workstream | Checklist | Primary ownership |
| --- | --- | --- |
| Backend/API | [`backend-api.md`](backend-api.md) | `self-service-api`: spec amendments, OpenAPI, migrations, Java services/controllers, authorization, search, AI integration, backend tests |
| Portal/UI | [`portal-ui.md`](portal-ui.md) | `self-service-portal`: routes, guards, API clients/models, Asset Hub screens, role UI, accessibility, portal tests |

Each task is checked only in its owning file. Cross-workstream acceptance checks appear below so
they are not duplicated and allowed to drift.

## Locked scope

- Employee-only Asset Hub.
- Manual metadata and source-URL submission; SharePoint URL storage only.
- No Microsoft Graph integration or source-content ingestion.
- `ASSET_REVIEWER` is internal-only and independent of other privileged roles.
- `SUPERADMIN` remains database-managed.
- Superadmins manage multiple allowlisted roles for internal employees.
- No self-approval.
- Last approved revision remains visible while a working revision is reviewed.
- POC assets may optionally link to existing hosted POCs and expose Launch when actually launchable.
- Blog/article template validation is an extension point until templates are provided.
- No reuse workflow, metric, event, or UI.
- UI follows the self-service portal design system; the supplied standalone mockup is reference only.

## Coordination order

1. Backend owner finalizes the OpenAPI shapes, migration model, state transitions, and role policy.
2. Backend owner generates sources and commits the contract boundary before either track changes
   generated models independently.
3. Portal owner consumes the committed OpenAPI contract through typed local models/API clients and
   builds routes/screens using mocked responses only where a backend path is not yet available.
4. Both owners integrate one vertical slice: internal employee creates a draft, submits it, a
   different reviewer approves it, and it appears in search.
5. Add POC launch behavior, AI suggestions/semantic search, role administration, telemetry, and
   failure-state coverage incrementally after the vertical slice passes.

## Required implementation phases

**Status (2026-09-10):** Phase 0 done (`5de7764`) and Phase 1's backend half done (`520b4ed`) — see
`backend-api.md`'s Handoff evidence for the full exit-test verification and known gaps. Phase 1's
portal half has not started. Phase 2/3 not started.

### Phase 0 — contract freeze (backend owner)

- Add every agreed Asset Hub path/schema and stable error code to OpenAPI.
- Add the exact migration design and Java package skeleton.
- Run `./mvnw generate-sources` (PowerShell: `.\mvnw.cmd generate-sources`).
- Commit the generated-contract boundary before portal integration begins.

### Phase 1 — authorization and vertical slice (backend and portal in parallel after Phase 0)

- Backend: internal guard, assets/revisions/reviews, no-self-review, approved-only keyword search
  (via `asset_search_documents`' `tsvector` column, created in this phase — the `vector` embedding
  column and pgvector extension stay Phase 3 only).
- Portal: guards, routes, discovery, editor, My assets, reviewer queue using the frozen contract.
- Exit test: internal employee A creates/submits; reviewer B approves; A and B can discover the
  approved revision; editing creates a new draft without removing the approved result.

### Phase 2 — POC association, role administration, feedback and telemetry

- Backend and portal complete their matching sections against the same committed schemas.
- Exit test: internal superadmin assigns multiple managed roles; linked ready POC launches; unhosted
  POC remains discoverable; feedback/events contain no reuse semantics.

### Phase 3 — intelligence

- First update the feature spec with the chosen AI provider, embedding model, fixed dimensions, and
  production/local configuration.
- Then add metadata suggestions, pgvector storage, semantic ranking, retry/fallback behavior, and UI.
- Exit test: semantic results use approved catalog metadata only and disabling the provider falls
  back to a fully usable keyword/manual-review experience.

BLOG/ARTICLE template scoring is not part of Phase 3 until the user supplies templates and rubrics.

## Conflict boundaries

- Backend owner owns `openapi/**`, API migrations, and `src/main|test/java/**` in
  `self-service-api` for this feature.
- Portal owner owns Asset Hub and employee-role UI files in `self-service-portal`.
- Do not copy generated Java models into portal code or edit generated Java sources directly.
- Changes to shared response shapes are proposed in the backend checklist/contract first.
- Changes to the portal's global theme or `.sui-*` classes require an explicit shared design-system
  decision; Asset Hub should normally compose existing classes and components.

## Cross-workstream acceptance

- [ ] An external user receives `403` from every Asset Hub API and cannot activate Asset Hub routes.
- [ ] An internal employee can create, edit, submit, and track their own asset.
- [ ] A different internal `ASSET_REVIEWER` can request changes, reject, or approve the submitted
      revision.
- [ ] The submitter and revision author cannot review that revision even if they hold
      `ASSET_REVIEWER`.
- [ ] Editing an approved asset creates a working revision while the approved revision remains in
      discovery and search.
- [ ] Only the exact approved revision becomes the new searchable revision after approval.
- [ ] SharePoint URLs are stored and opened without any backend source fetch.
- [ ] A linked, ready POC shows Launch and uses the existing launch/workspace flow; an unhosted POC
      asset remains discoverable without a misleading Launch action.
- [ ] An internal superadmin can assign `ADMIN` and `ASSET_REVIEWER` together to an internal
      employee, but cannot assign `SUPERADMIN` or target an external account.
- [ ] Removing `ASSET_REVIEWER` prevents new review operations after the role change is reflected in
      the user's current token.
- [ ] Keyword and semantic searches return approved catalog metadata only.
- [ ] AI failure leaves manual submission and review usable.
- [ ] No screen or API exposes a reuse action or reuse metric.
- [ ] Portal pages pass automated tests, production build, keyboard checks, WCAG AA contrast, and
      AXE checks.
- [ ] API tests and OpenAPI generation pass from a clean checkout.

## Delivery gates

- **Contract gate:** OpenAPI compiles, migrations are reviewed, state transitions and authorization
  rules have tests.
- **Vertical-slice gate:** create → submit → independent review → approved discovery works without
  AI or semantic search.
- **Intelligence gate:** metadata suggestions and semantic retrieval operate only on entered,
  approved catalog metadata and degrade safely.
- **Release gate:** cross-workstream acceptance checks pass and feature flags/configuration default
  safely for existing deployments.

## Completion evidence

Each owner records the following at the bottom of their checklist before handoff:

```text
Implementation commit(s): <hashes>
Tests run: <exact commands>
Result: <pass/fail and counts>
Deferred items: <checkboxes plus reason>
Known risks: <none or concise list>
```

Backend minimum commands:

```powershell
.\mvnw.cmd generate-sources
.\mvnw.cmd test
git status --short
```

Portal minimum commands, run from `self-service-portal`:

```powershell
npm test -- --run
npm run build
git status --short
```

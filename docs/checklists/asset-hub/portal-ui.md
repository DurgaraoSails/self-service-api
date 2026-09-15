# Asset Hub portal/UI checklist

Scope: `self-service-portal`. This owner builds the Asset Hub experience inside the existing portal
shell and design system. The supplied `ai_asset_hub_mockup.html` is useful for interaction patterns
only; it does not override the portal's code, product, accessibility, or visual rules.

## Expected files and routes

Read `self-service-portal/AGENTS.md` before editing. Expected locations are:

```text
src/app/app.routes.ts
src/app/components/app-shell/app-shell.ts
src/app/core/auth/auth.ts
src/app/core/asset/asset.models.ts
src/app/core/asset/asset-api.ts
src/app/core/asset/asset-review-api.ts
src/app/core/asset/asset-guards.ts
src/app/components/asset-hub/{asset-hub.ts,asset-hub.html}
src/app/components/asset-detail/{asset-detail.ts,asset-detail.html}
src/app/components/asset-editor/{asset-editor.ts,asset-editor.html}
src/app/components/my-assets/{my-assets.ts,my-assets.html}
src/app/components/asset-reviews/{asset-reviews.ts,asset-reviews.html}
src/app/components/asset-review-detail/{asset-review-detail.ts,asset-review-detail.html}
src/app/components/employee-roles/{employee-roles.ts,employee-roles.html}
```

Prefer existing shared components and add focused child components only when a template would
otherwise become large. Do not add feature styling to global `theme.css` or `components.css` unless
the pattern is genuinely reusable outside Asset Hub and is reviewed as a design-system change.

Use these route contracts:

| Route | Guard | Screen |
| --- | --- | --- |
| `/assets` | internal | Discovery/search |
| `/assets/new` | internal | Create draft |
| `/assets/mine` | internal | Current employee's submissions |
| `/assets/:id` | internal | Approved detail/source/optional launch |
| `/assets/:id/edit` | internal plus API ownership | Working revision editor |
| `/assets/reviews` | internal reviewer | Pending queue |
| `/assets/reviews/:revisionId` | internal reviewer | Frozen revision review |
| `/employees/roles` | internal superadmin | Multi-role management |

Keep static paths such as `new`, `mine`, and `reviews` ahead of parameterized routes where route
matching could otherwise treat them as asset IDs.

## 1. Contract consumption and feature structure

- [x] Wait for or agree on the committed Asset Hub OpenAPI shapes before fixing portal models.
- [x] Add typed Asset Hub models without `any` and map transport enums explicitly where needed.
- [x] Add focused API services for assets/search, reviews, AI suggestions, feedback/events, and
      employee role administration.
- [x] Keep feature state in signals/computed values and keep transformations pure.
- [x] Use standalone, `OnPush` components and lazy-load Asset Hub routes.
- [x] Keep generated/backend naming consistent instead of inventing a second portal vocabulary.
      *(Transport DTOs remain authoritative; `AssetRecord` is isolated as a UI projection.)*
- [x] Provide stable loading, empty, error, retry, and stale-revision states for every API surface.

## 2. Routing, navigation, and guards

- [x] Add an `internalGuard` for all Asset Hub routes and retain backend enforcement.
- [x] Add an `assetReviewerGuard` requiring both internal account and `ASSET_REVIEWER`.
- [x] Add a superadmin/internal guard for employee role administration.
- [x] Add Asset Hub to the existing `AppShell` navigation instead of introducing a new sidebar or
      application shell.
- [x] Define lazy routes for discovery, asset detail, submit/edit, my submissions, reviewer queue,
      and employee-role administration.
- [x] Show reviewer navigation only when the current token carries `ASSET_REVIEWER`.
- [x] Do not make `ADMIN` or `SUPERADMIN` imply reviewer navigation.
- [x] Add forbidden/redirect behavior that preserves a safe return destination.
- [x] Add one top-level `Asset Hub` entry for internal users. Put `My assets` and reviewer-only
      `Reviews` inside an existing group/dropdown pattern rather than crowding the top navigation.

## 3. Design-system compliance

- [x] Compose the existing `app-page-header`, `app-status-badge`, `app-tabs`, `app-empty-state`,
      `app-skeleton`, dialogs, and other shared components before creating new primitives.
- [x] Use `.sui-card`, `.sui-btn*`, `.sui-input`, `.sui-select`, `.sui-textarea`, `.sui-label`,
      `.sui-hint`, `.sui-error`, and semantic theme tokens.
- [x] Use layout utilities for spacing/grid only; do not create a second button/input/card system.
- [x] Support the portal's light and dark themes with no hardcoded mockup palette.
- [x] Use the portal's typography; do not import the mockup's Fraunces/Inter pairing.
- [x] Follow existing max-width, page padding, header, card, and responsive shell patterns.
- [x] Keep visible focus, native semantics, WCAG AA contrast, reduced-motion behavior, and usable
      touch targets.
- [ ] Pass AXE checks at desktop and narrow layouts with keyboard-only operation.

## 4. Discovery experience

- [x] Build a search-first Asset Hub landing page using the existing page header and search-input
      treatment.
- [x] Support the shared query input with explicit submit, clear, and pending states. *(Keyword is
      active; the same input can consume semantic results after the deliberately deferred backend phase.)*
- [x] Provide filters for asset type, tags, owner, and launchable POCs without hiding active filter
      state.
- [x] Render responsive asset cards showing type, title, approved summary, owner, tags, and useful
      review/updated context.
- [x] Keep similarity/relevance values understandable; do not show invented percentages when the API
      does not provide a calibrated value.
- [x] Provide no-results guidance and a clear route to submit a missing asset.
- [x] Never render working revision text or reviewer feedback in public discovery cards.
- [x] Ensure query/filter changes do not blank already rendered results during background refresh.

## 5. Asset detail and POC launch

- [x] Show approved metadata, owner, type, tags, review freshness, and original source action.
- [x] Label the source action clearly and explain that source-system permissions still apply.
- [x] Open external source URLs with safe `noopener`/`noreferrer` behavior.
- [x] For a linked ready POC, show Launch using the established primary-action pattern and existing
      workspace flow.
- [x] For unhosted/unready POC assets, omit Launch or show an honest unavailable state; never lead to
      a dead URL.
- [x] Keep hosting status separate from asset approval status.
- [x] Let an authorized contributor/reviewer reach the working revision without replacing the
      approved detail shown to ordinary employees.

## 6. Submission and editing

- [x] Use a reactive form with asset type, title, owner, summary/problem fields, tags, source URL,
      and conditional POC association.
- [x] Show a plain-language notice that catalog metadata is visible to internal employees and must
      not contain restricted source content.
- [x] Describe SharePoint fields as stored links only; do not offer upload, preview, import, or sync.
- [x] Validate URL format client-side while treating the backend as authoritative.
- [x] Provide draft save and explicit Submit for review actions.
- [x] Surface server validation as an accessible form-level error and preserve unsaved input on
      errors. *(The current API error contract does not carry a field path.)*
- [x] Include the expected revision/version on edits and present a recoverable stale-edit conflict.
- [x] When editing an approved asset, clearly distinguish the currently approved revision from the
      new working revision.
- [x] Show that the old approved revision stays live until the edit is approved.
- [x] Add My submissions with draft, pending, changes-requested, rejected, and approved states and
      clear next actions.

## 7. AI suggestion panel

- [x] Adapt the mockup's adjacent suggestion pattern using portal cards and semantic status
      components.
- [x] State that suggestions use only the fields entered in Asset Hub and do not inspect the source
      link.
- [x] Distinguish generated proposals from saved revision values.
- [x] Provide explicit Accept/Apply behavior for summary and tags; do not silently overwrite input.
- [x] Handle pending, failed, stale-after-edit, retry, and disabled-provider states without blocking
      manual work.
- [x] Do not imply automatic reviewer assignment, notifications, duplicate certainty, or source
      inspection unless later implemented.
- [x] Leave BLOG/ARTICLE validation score UI behind a disabled extension boundary until templates
      and rubrics are supplied.

## 8. Reviewer queue and review detail

- [x] Gate the queue and detail routes with `assetReviewerGuard`.
- [x] List pending revisions with age, type, owner, submitter, and enough metadata to prioritize.
- [x] Exclude or disable revisions the current reviewer authored/submitted, while relying on the API
      for final enforcement.
- [x] Compare working and last-approved revisions when an existing asset is being changed.
- [x] Provide Approve, Request changes, and Reject actions with appropriate feedback requirements and
      confirmation for consequential decisions.
- [x] Send the exact revision ID and expected version with the decision.
- [x] Handle a competing reviewer decision as a stale state and refresh without losing typed
      feedback unexpectedly.
- [x] Display append-only decision history only to authorized contributors/reviewers.
- [x] Move focus and announce success/error after dialogs and review actions.

## 9. Internal employee role administration

- [x] Add an internal employee role-management surface accessible only to an internal superadmin.
- [x] Keep it distinct from customer trial management even if shared table/search components are
      reused.
- [x] Show current `ADMIN` and `ASSET_REVIEWER` assignments as independent controls so both can be
      selected.
- [x] Never offer a `SUPERADMIN` control; explain that it is database-managed where useful.
- [x] Prevent selecting the signed-in superadmin as the target and handle backend rejection.
- [x] Submit the complete desired managed-role set atomically rather than firing independent
      role-toggle requests that can race.
- [x] Display when refreshed sign-in/token state may be required for a changed employee to observe
      their new permissions.
- [x] Confirm destructive role removal and preserve focus/selection after refresh.

## 10. Feedback and measurement hooks

- [x] Provide concise helpful/not-helpful feedback and optional bounded comment on asset detail.
- [x] Record source-open and POC-launch events without labeling either as reuse.
- [x] Correlate search → detail/source/launch using the backend's privacy-minimized session contract.
- [x] Do not display a reuse count or “reused this quarter” metric from the mockup.
- [x] Add reviewer queue age/turnaround UI only when the backend defines authoritative calculations.

## 11. Testing and delivery

- [x] Unit-test guards for external, internal, reviewer, admin, and superadmin combinations.
- [x] Unit-test asset card launch/source behavior and absence of actions in invalid states.
      *(2026-09-15: `asset-hub.spec.ts` filter/launchable coverage, `asset-detail.spec.ts`
      canArchive/canEdit owner-vs-unrelated-employee gating.)*
- [x] Test reactive-form validation, draft preservation, AI failure, and stale-revision recovery.
      *(2026-09-15: `asset-editor.spec.ts` — required-field/URL-pattern validation, AI
      pending/succeeded/FAILED/503-unavailable states against the real HTTP layer, and the
      stale-edit-conflict recovery message with the employee's unsaved input preserved, tested
      against the real `AssetStore` — see the `AssetStore.track()` fix below.)*
- [x] Test no-self-review UI behavior and backend `403`/`409` handling. *(2026-09-15:
      `asset-review-detail.spec.ts` — self-review flagging for submitter/author, the store-layer
      block even if a confirm somehow fires, and the competing-reviewer 409 recovery message with
      typed feedback preserved.)*
- [x] Test approved-plus-working revision presentation. *(2026-09-15: `asset-editor.spec.ts`'s
      `editReady` gating for a non-DRAFT working revision; `asset-review-detail.spec.ts`'s
      previous-approved-revision comparison via preview fixtures.)*
- [x] Test atomic multi-role selection and exclusion of `SUPERADMIN`. *(2026-09-15:
      `employee-roles.spec.ts` — signed-in-superadmin exclusion, immediate-add vs.
      confirm-then-remove, full-role-set (not single-toggle) PUT body, and SUPERADMIN never
      offered as a checkbox.)*
- [x] Test loading, empty, no-results, error, retry, and narrow-screen states. *(2026-09-15: added
      across all 7 new spec files for loading/empty/error/retry; narrow-screen/responsive layout
      is not covered — that needs a real viewport-driven test, tracked with AXE below.)*
- [x] Run portal tests and production build. *(2026-09-15: `npx ng test --watch=false`: 594
      passed, 0 failed — up from 504; `npm run build` and `npm run build -- -c asset-hub` both
      pass.)*
- [ ] Run AXE and keyboard checks on discovery, submission, detail, reviewer, and role-management
      screens in light and dark mode. *(Still deliberately deferred — confirmed with the user
      2026-09-15 as a separate follow-up task; no AXE tooling exists in this repo yet.)*
- [x] Update this checklist and the central feature spec with deliberate deferrals or changed
      decisions.

## Handoff evidence

```text
Implementation commit(s):
  082c4ac  Manual workflow completion — state isolation, pagination, lifecycle actions, review
           history/confirmation, feature gating, feedback/session correlation, error recovery
  0eb0538  Final recovery states — non-blanking refresh, stale-edit recovery, AI retry handling,
           and review-result focus/announcements

Tests run:
  npx ng test --watch=false  — 504 tests passed
  npm run build              — production build passed
  Browser smoke              — discovery, My assets, reviewer queue, review confirmation passed

Result: Manual Asset Hub portal workflow passes existing automation, production compilation, and
  preview smoke verification.

Deferred items:
  - Component-level Asset Hub test generation — next agreed phase (§11 open checkboxes).
  - AXE, keyboard-only, narrow-layout, and light/dark accessibility verification.
  - Semantic retrieval and real AI provider behavior — deliberately deferred with backend Phase 3.
  - BLOG/ARTICLE template scoring — deliberately disabled until templates/rubrics are supplied.

Known risks:
  - The current API error envelope has no field path, so backend validation is shown at form level.
  - The already-running backend was not restarted; authenticated current-commit integration smoke
    remains part of the next verification phase.
```

**Update 2026-09-15 — component-level test-coverage pass.** New spec files for all 7 Asset Hub
components: `asset-hub.spec.ts`, `asset-detail.spec.ts`, `asset-editor.spec.ts`, `my-assets.spec.ts`,
`asset-reviews.spec.ts`, `asset-review-detail.spec.ts`, `employee-roles.spec.ts`. Each mixes two
styles: a `(preview data)` suite against the existing `ASSET_HUB_PREVIEW`/fixture convention already
established by `asset-store.spec.ts` (fast, no HTTP mocking, used for rendering/interaction/
validation logic), and an `(HTTP-backed)` suite using `HttpTestingController` for loading/error/
retry/pagination states that only exist in the real (non-preview) `AssetStore` path — matching
`poc-form-modal.spec.ts`/`poc-deployment-panel.spec.ts`'s established convention.

```text
Tests run:
  npx ng test --watch=false  — 594 tests passed, 0 failed (up from 504; +90 new cases)
  npm run build              — production build passed
  npm run build -- -c asset-hub — passed

Two real bugs in AssetStore.track() were caught and fixed by writing these tests, not by test-side
  workarounds — both are production code fixes in src/app/core/asset/asset-store.ts:

  1. NG0602 ("effect() cannot be called from within a reactive context"). AssetDetail, AssetEditor,
     and AssetReviewDetail each wrap their initial id-driven load in a component-constructor
     effect() that calls an AssetStore method (loadApproved/loadWorking/loadReview), which itself
     creates a fresh Angular effect() inside AssetStore.track() — a nested effect() creation
     Angular's reactivity primitives disallow, and driving it through TestBed's synchronous
     fixture.detectChanges() throws immediately. Fixed by wrapping the effect() *creation* in
     untracked(), which clears the active reactive consumer for that call — exactly what the
     NG0602 assertion checks for, without changing what the new effect itself depends on.

  2. A genuine infinite loop, masked by bug 1 until it was fixed: AssetReviewDetail.loadReview()'s
     onData callback reads workingState() (via findReview(), to merge into the freshly-loaded
     record) and then writes workingState() (via upsertWorking()) — the same signal, read then
     written, inside one effect execution. Angular marks the effect dirty again on its own write to
     a signal it read, and since .update() always produces a new array reference, the effect never
     converges — it re-runs forever (confirmed via real CPU spin, not a hang waiting on something).
     Fixed by wrapping the onData/onError callback *invocation* in untracked() too: those callbacks
     only care about the resolved value the moment it arrives, they were never meant to be reactive
     to whatever else they happen to read, so signal reads inside them should not become
     dependencies of track()'s own effect.

  Both fixes are the same primitive (untracked()) applied at two different points in the same
  method; see the doc comment on AssetStore.track() for the full explanation. Confirmed fixed by
  restoring the originally-intended real-HTTP-backed tests for all 3 components (no fake-store
  workaround needed) and by running the full suite + both production builds clean afterward.

Deferred items:
  - AXE/keyboard/narrow-layout/light-dark accessibility checks — separate follow-up, confirmed with
    the user; no tooling installed yet.

Known risks:
  - Whether bug 2 (the infinite loop) could have manifested in the live app before this fix, or was
    only reachable once bug 1 was independently fixed, was not established — Angular's real
    scheduler may have different iteration-limit behavior than TestBed's forced-synchronous
    detectChanges(). Worth treating as a real fix either way, not merely a test-environment quirk.
```

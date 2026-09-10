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

- [ ] Wait for or agree on the committed Asset Hub OpenAPI shapes before fixing portal models.
- [ ] Add typed Asset Hub models without `any` and map transport enums explicitly where needed.
- [ ] Add focused API services for assets/search, reviews, AI suggestions, feedback/events, and
      employee role administration.
- [ ] Keep feature state in signals/computed values and keep transformations pure.
- [ ] Use standalone, `OnPush` components and lazy-load Asset Hub routes.
- [ ] Keep generated/backend naming consistent instead of inventing a second portal vocabulary.
- [ ] Provide stable loading, empty, error, retry, and stale-revision states for every API surface.

## 2. Routing, navigation, and guards

- [ ] Add an `internalGuard` for all Asset Hub routes and retain backend enforcement.
- [ ] Add an `assetReviewerGuard` requiring both internal account and `ASSET_REVIEWER`.
- [ ] Add a superadmin/internal guard for employee role administration.
- [ ] Add Asset Hub to the existing `AppShell` navigation instead of introducing a new sidebar or
      application shell.
- [ ] Define lazy routes for discovery, asset detail, submit/edit, my submissions, reviewer queue,
      and employee-role administration.
- [ ] Show reviewer navigation only when the current token carries `ASSET_REVIEWER`.
- [ ] Do not make `ADMIN` or `SUPERADMIN` imply reviewer navigation.
- [ ] Add forbidden/redirect behavior that preserves a safe return destination.
- [ ] Add one top-level `Asset Hub` entry for internal users. Put `My assets` and reviewer-only
      `Reviews` inside an existing group/dropdown pattern rather than crowding the top navigation.

## 3. Design-system compliance

- [ ] Compose the existing `app-page-header`, `app-status-badge`, `app-tabs`, `app-empty-state`,
      `app-skeleton`, dialogs, and other shared components before creating new primitives.
- [ ] Use `.sui-card`, `.sui-btn*`, `.sui-input`, `.sui-select`, `.sui-textarea`, `.sui-label`,
      `.sui-hint`, `.sui-error`, and semantic theme tokens.
- [ ] Use layout utilities for spacing/grid only; do not create a second button/input/card system.
- [ ] Support the portal's light and dark themes with no hardcoded mockup palette.
- [ ] Use the portal's typography; do not import the mockup's Fraunces/Inter pairing.
- [ ] Follow existing max-width, page padding, header, card, and responsive shell patterns.
- [ ] Keep visible focus, native semantics, WCAG AA contrast, reduced-motion behavior, and usable
      touch targets.
- [ ] Pass AXE checks at desktop and narrow layouts with keyboard-only operation.

## 4. Discovery experience

- [ ] Build a search-first Asset Hub landing page using the existing page header and search-input
      treatment.
- [ ] Support keyword/semantic query input with explicit submit, clear, and pending states.
- [ ] Provide filters for asset type, tags, owner, and launchable POCs without hiding active filter
      state.
- [ ] Render responsive asset cards showing type, title, approved summary, owner, tags, and useful
      review/updated context.
- [ ] Keep similarity/relevance values understandable; do not show invented percentages when the API
      does not provide a calibrated value.
- [ ] Provide no-results guidance and a clear route to submit a missing asset.
- [ ] Never render working revision text or reviewer feedback in public discovery cards.
- [ ] Ensure query/filter changes do not blank already rendered results during background refresh.

## 5. Asset detail and POC launch

- [ ] Show approved metadata, owner, type, tags, review freshness, and original source action.
- [ ] Label the source action clearly and explain that source-system permissions still apply.
- [ ] Open external source URLs with safe `noopener`/`noreferrer` behavior.
- [ ] For a linked ready POC, show Launch using the established primary-action pattern and existing
      workspace flow.
- [ ] For unhosted/unready POC assets, omit Launch or show an honest unavailable state; never lead to
      a dead URL.
- [ ] Keep hosting status separate from asset approval status.
- [ ] Let an authorized contributor/reviewer reach the working revision without replacing the
      approved detail shown to ordinary employees.

## 6. Submission and editing

- [ ] Use a reactive form with asset type, title, owner, summary/problem fields, tags, source URL,
      and conditional POC association.
- [ ] Show a plain-language notice that catalog metadata is visible to internal employees and must
      not contain restricted source content.
- [ ] Describe SharePoint fields as stored links only; do not offer upload, preview, import, or sync.
- [ ] Validate URL format client-side while treating the backend as authoritative.
- [ ] Provide draft save and explicit Submit for review actions.
- [ ] Surface server validation against the correct field and preserve unsaved input on errors.
- [ ] Include the expected revision/version on edits and present a recoverable stale-edit conflict.
- [ ] When editing an approved asset, clearly distinguish the currently approved revision from the
      new working revision.
- [ ] Show that the old approved revision stays live until the edit is approved.
- [ ] Add My submissions with draft, pending, changes-requested, rejected, and approved states and
      clear next actions.

## 7. AI suggestion panel

- [ ] Adapt the mockup's adjacent suggestion pattern using portal cards and semantic status
      components.
- [ ] State that suggestions use only the fields entered in Asset Hub and do not inspect the source
      link.
- [ ] Distinguish generated proposals from saved revision values.
- [ ] Provide explicit Accept/Apply behavior for summary and tags; do not silently overwrite input.
- [ ] Handle pending, failed, stale-after-edit, retry, and disabled-provider states without blocking
      manual work.
- [ ] Do not imply automatic reviewer assignment, notifications, duplicate certainty, or source
      inspection unless later implemented.
- [ ] Leave BLOG/ARTICLE validation score UI behind a disabled extension boundary until templates
      and rubrics are supplied.

## 8. Reviewer queue and review detail

- [ ] Gate the queue and detail routes with `assetReviewerGuard`.
- [ ] List pending revisions with age, type, owner, submitter, and enough metadata to prioritize.
- [ ] Exclude or disable revisions the current reviewer authored/submitted, while relying on the API
      for final enforcement.
- [ ] Compare working and last-approved revisions when an existing asset is being changed.
- [ ] Provide Approve, Request changes, and Reject actions with appropriate feedback requirements and
      confirmation for consequential decisions.
- [ ] Send the exact revision ID and expected version with the decision.
- [ ] Handle a competing reviewer decision as a stale state and refresh without losing typed
      feedback unexpectedly.
- [ ] Display append-only decision history only to authorized contributors/reviewers.
- [ ] Move focus and announce success/error after dialogs and review actions.

## 9. Internal employee role administration

- [ ] Add an internal employee role-management surface accessible only to an internal superadmin.
- [ ] Keep it distinct from customer trial management even if shared table/search components are
      reused.
- [ ] Show current `ADMIN` and `ASSET_REVIEWER` assignments as independent controls so both can be
      selected.
- [ ] Never offer a `SUPERADMIN` control; explain that it is database-managed where useful.
- [ ] Prevent selecting the signed-in superadmin as the target and handle backend rejection.
- [ ] Submit the complete desired managed-role set atomically rather than firing independent
      role-toggle requests that can race.
- [ ] Display when refreshed sign-in/token state may be required for a changed employee to observe
      their new permissions.
- [ ] Confirm destructive role removal and preserve focus/selection after refresh.

## 10. Feedback and measurement hooks

- [ ] Provide concise helpful/not-helpful feedback and optional bounded comment on asset detail.
- [ ] Record source-open and POC-launch events without labeling either as reuse.
- [ ] Correlate search → detail/source/launch using the backend's privacy-minimized session contract.
- [ ] Do not display a reuse count or “reused this quarter” metric from the mockup.
- [ ] Add reviewer queue age/turnaround UI only when the backend defines authoritative calculations.

## 11. Testing and delivery

- [ ] Unit-test guards for external, internal, reviewer, admin, and superadmin combinations.
- [ ] Unit-test asset card launch/source behavior and absence of actions in invalid states.
- [ ] Test reactive-form validation, draft preservation, AI failure, and stale-revision recovery.
- [ ] Test no-self-review UI behavior and backend `403`/`409` handling.
- [ ] Test approved-plus-working revision presentation.
- [ ] Test atomic multi-role selection and exclusion of `SUPERADMIN`.
- [ ] Test loading, empty, no-results, error, retry, and narrow-screen states.
- [ ] Run portal tests and production build.
- [ ] Run AXE and keyboard checks on discovery, submission, detail, reviewer, and role-management
      screens in light and dark mode.
- [ ] Update this checklist and the central feature spec with deliberate deferrals or changed
      decisions.

## Handoff evidence

Fill this in before declaring the portal work complete:

```text
Implementation commit(s):
Tests run:
Result:
Deferred items:
Known risks:
```

# Tag-Driven POC Deployment

## Status

Draft

## Overview / Purpose

A POC's version number is currently invented by this platform and never checked against the
repository. `PocDeploymentService.allocateNextVersion` reads the highest row in `poc_versions`,
bumps the patch, and starts a brand-new POC at `1.0.1` — regardless of what tags the repository
already has. `PipelineRunner` then asks GitHub to create that tag, and
`GitHubService.verifyExistingTagMatches` refuses when the name is taken by a different commit:

> Tag 1.0.1 already exists on owner/repo at commit abc…, but this deployment is for def…. Refusing
> to reuse a version label for different code.

That refusal is correct in itself — silently moving a released label would make it meaningless — but
it makes any repository with pre-existing tags **undeployable**, which is where admins are stuck
today. The platform's numbering and the repository's tags are two sources of truth for one fact.

This spec makes the repository the single source of truth. The platform stops inventing versions:
it reads the tags that exist, lets an admin deploy one of them directly, and when a new version is
wanted, derives the next name from what git already has so it cannot collide. It also moves the
repository lookups off the page-load path into a cached, explicitly-refreshed snapshot.

## Requirements

1. An admin can **create and deploy a new version**, **deploy a tag that already exists**, and
   **return to a version already deployed**. These were specified as three separate controls; they
   are delivered as one button plus one list — see "One list of versions, not two", which explains
   why, and which of the three each row satisfies.
2. The version list shows the **3 most recent tags** (plus any older version this POC has already
   deployed). A tag whose commit is the head of the deploy branch is marked current; otherwise it
   carries a warning icon explaining it is behind.
3. The list is hidden entirely when the repository has no tags and the POC has never been deployed.
4. "Deploy new version" derives its tag name from the repository's existing tags and must never
   choose a name that already exists.
5. When the token cannot create tags, "Deploy new version" is disabled with an explanation and two
   suggested remedies (add the platform account as a collaborator; or create the tag by hand, which
   the version list will then offer).
6. Repository state is **not** fetched during POC creation or on every page load. It is captured
   asynchronously after creation, stored, and refreshed on demand.
7. The deployment page shows the repository URL read-only with a refresh control.
8. Returning to an already-built version deploys the stored image and must not rebuild. A version whose image is no longer in
   Artifact Registry cannot be deployed that way.
9. Deploying a tag with no built image runs the full pipeline: clone, build, push, deploy.
10. Every deploy path must remain safe to retry.

## Architecture Decisions

### Versions become tag names, not numbers the platform owns

`poc_versions` currently decomposes a version into `major`/`minor`/`patch` and rebuilds the label
from them. With git as the source of truth, the label is primary and the parts are derived — and for
tags that are not semver at all, there are no parts.

**Two existing database constraints block this outright and must change** (`V7__create_poc_versions_table.sql`):

```sql
CONSTRAINT uq_poc_versions_poc_number UNIQUE (poc_id, major, minor, patch),
CONSTRAINT ck_poc_versions_patch_range CHECK (patch BETWEEN 1 AND 20)
```

A repository whose newest tag is `1.0.25` cannot be recorded at all — the CHECK rejects the insert
after the tag has already been created on GitHub, leaving the repository tagged and the platform
with no row for it. This is the single most likely way the feature breaks in production, and it is
invisible until a repo crosses patch 20.

The migration therefore drops the CHECK, drops the numeric unique constraint, and adds
`UNIQUE (poc_id, version_label)` — which is the invariant that actually matters now: one row per tag
per POC. `major`/`minor`/`patch` become nullable, populated only when the tag parses as semver, and
used only for ordering.

### Deriving the next tag name

When an admin clicks "Deploy new version", the platform reads the cached tag list, takes the highest
**semver-parseable** tag, increments its patch, and — critically — keeps incrementing while the
candidate name is already taken. Tags that do not parse (`latest`, `release-2024`, `demo`) are
ignored for derivation but still shown in the version list, because an admin may legitimately want to
deploy one.

A repository with no tags at all starts at `1.0.0`. (Today's code starts at `1.0.1`, which is odd
and worth correcting while the numbering is being rewritten anyway.)

**A repository whose tags are all non-semver cannot have a new version derived**, and this platform
only hosts semver repositories. Rather than proposing `1.0.0` into a repository that clearly numbers
its releases some other way — and then colliding upward from there — "Deploy new version" is
disabled, with a message saying the repository's existing tags are not semver and that a semver tag
must be created for the platform to continue from. Those non-semver tags remain deployable from the
version list, so the POC is not stuck: the admin can ship one immediately and fix the numbering later.

This is the same disabled-with-a-reason treatment as the no-push-access case, and shares its UI.

The derived name is computed from the **cached** list, so a tag pushed since the last refresh could
still collide. That is why derivation is a suggestion, not a guarantee: the deploy path re-reads the
tags immediately before creating one, and retries the derivation once against fresh data. If it
still collides, the deploy fails with a message telling the admin to refresh — a race that needs two
people acting within seconds, and which cannot corrupt anything.

### Deploying an existing tag must not require push access

This is a subtle contradiction in the requirements as written, and it would break requirement 5's
own escape hatch. `PipelineRunner.runBuildAndDeploy` currently calls `gitHubService.requirePushAccess(repo)`
unconditionally, before tagging. If an admin is told "create the tag manually and we'll deploy it",
but the pipeline still demands push access to deploy *any* tag, that advice cannot be followed.

`requirePushAccess` therefore moves from "always" to "only when this deploy will create a tag".
Deploying an existing tag needs read access only — which the repository read already proved.

### Three deploy paths, one pipeline

| Action | Tag | Build | Deploy |
|---|---|---|---|
| Deploy new version | created by platform | yes | yes |
| Deploy existing tag | already exists, untouched | yes | yes |
| Roll back | already exists, untouched | **no** | yes |

Rollback already works this way (`redeployVersion` → `deploymentTrigger.redeploy` → the executor's
`deploy` only). Deploying an existing tag is the genuinely new path: it reuses `BUILD_AND_DEPLOY`
but skips tag creation, because the tag is the input rather than the output.

The tag's own commit becomes the version's `commit_sha` — read from the tag, never from the branch
head. Deploying tag `1.0.3` must build `1.0.3`'s code even if `main` has moved on, or the version
label lies about what shipped.

### Freshness is exact commit equality

A tag is "current" when its commit SHA equals the head of the deploy branch
(`pipeline.deploy-branch`, or the repository default). Anything else shows the warning icon. This
needs no extra API call — the branch head is already fetched for the snapshot.

Rejected for now: GitHub's compare API, which would say *"3 commits behind"* and is far more useful
for deciding which tag to pick, but costs one call per tag shown. Recorded under Future Work; the
snapshot is the natural place to add it later since it is refreshed rarely.

### Rollback verifies the image exists

Nothing in this codebase talks to Artifact Registry today — `GcpProperties.imageUri` only builds a
URI string. An image can be deleted by retention policy or by hand, and rollback would then fail
inside Cloud Build with an obscure "image not found".

The snapshot records, per stored version, whether every one of its container images still resolves.
The UI greys out versions that fail, with a tooltip. The check runs during refresh, not on page
load, so it costs nothing at render time.

**This needs a new IAM binding.** self-service-api's service account needs
`roles/artifactregistry.reader` on the project (or on the `poc-images` repository) in
`self-service-terraform`. Without it every version reads as "image missing" and rollback is disabled
across the board — a failure that looks like data loss rather than a permissions gap, so the
refresh must record *why* the check failed and the UI must distinguish "image gone" from "could not
check".

### The snapshot, and why it is not a cache

The repository state is stored as ordinary rows, not in a cache abstraction (there is none in this
codebase — no Spring Cache, no Caffeine, no Redis). It is written by an explicit refresh and read by
everything else. Nothing expires it, because a value that silently vanishes mid-session is worse
here than one that is visibly stale with a timestamp and a refresh button.

Refresh runs: **once after POC creation** (asynchronously, so creation does not wait on GitHub),
**when the admin clicks refresh**, and **after any successful deploy** (the tag list has certainly
changed). No scheduler — that would be constant GitHub traffic proportional to catalogue size, for
data an admin looks at rarely.

**The existing async setup cannot safely absorb this.** `AsyncConfig` is bare `@EnableAsync` with no
`TaskExecutor` bean, so Spring falls back to `SimpleAsyncTaskExecutor`: a new unbounded thread per
call, no pool, no queue. That is survivable for `PipelineRunner` (a human triggers one deploy at a
time) but not for refresh-on-create, which a bulk import or an impatient admin could fan out. A
bounded `ThreadPoolTaskExecutor` must be added and named explicitly on the refresh method — and,
while there, `PipelineRunner` should move onto a pool too.

## Data Model

New migration `V12__create_poc_repo_status.sql`, plus alterations to `poc_versions`.

**`poc_repo_status`** — one row per POC, the snapshot:

| Column | Notes |
|---|---|
| `poc_id` | PK, FK → `pocs`, `ON DELETE CASCADE` |
| `default_branch` | as reported by GitHub |
| `deploy_branch` | the branch actually used, after `pipeline.deploy-branch` is applied |
| `head_commit_sha` | head of the deploy branch at refresh time |
| `can_create_tags` | from `permissions.push` |
| `is_archived`, `is_visible` | archived repos are read-only; invisible covers 404/private |
| `refreshed_at` | drives the "as of…" label in the UI |
| `refresh_error` | null on success; the message to show when the last refresh failed |

**`poc_repo_tags`** — child of the snapshot, one source of the version list:

| Column | Notes |
|---|---|
| `poc_id` | FK, `ON DELETE CASCADE` |
| `tag_name`, `commit_sha` | |
| `is_current` | commit equals `head_commit_sha` |
| `position` | preserves GitHub's newest-first order without re-sorting client-side |

Storing tags as rows rather than JSON keeps them queryable and matches the codebase's existing
style. Only the newest few are kept — the list shows 3, and storing more invites the question of
what "recent" means.

**`poc_versions`** changes: `version_label` becomes the identity (`UNIQUE (poc_id, version_label)`),
`major`/`minor`/`patch` become nullable and ordering-only, and both `uq_poc_versions_poc_number` and
`ck_poc_versions_patch_range` are dropped. A new `image_available` plus `image_checked_at` records
the Artifact Registry result per version.

Existing rows migrate cleanly: every current label is semver and unique per POC.

## API Surface

**One new read endpoint replaces several.** Opening the deployment tab currently fires five requests
— `getPocById` **twice** (both `PocSettingsPage` and `PocDeploymentPanel` fetch it independently),
plus `getVersions`, `getDeployments` and `getManifestPreview`, the last of which calls GitHub
synchronously on every load.

```
GET /pocs/{id}/deployment-overview
```

returns the POC's deploy-relevant fields, the repo snapshot (URL, branch, `refreshedAt`,
`canCreateTags`, `refreshError`), the tag list, the version list with `imageAvailable`, and recent
deployments — everything the page renders, in one round trip off the database with no GitHub call.

```
POST /pocs/{id}/repo-status/refresh     → 202, refreshes asynchronously
POST /pocs/{id}/deploy                  → body gains optional { "tag": "1.0.3" }
```

`POST /pocs/{id}/deploy` with no body keeps today's meaning (derive and create a tag); with a `tag`
it deploys that existing tag and creates nothing. Rollback (`POST /pocs/{id}/versions/{versionId}/redeploy`)
and retry are unchanged.

`GET /pocs/{id}/manifest-preview` stays for the preview panel but should read the snapshot's commit
rather than calling GitHub — that single endpoint is the redundant per-load network call today.

## UI Changes (self-service-portal)

All within `poc-deployment-panel.ts/.html`, plus the parent `poc-settings-page`.

**Repository header.** A read-only box above "Active version" showing the git URL, the branch, and
`Updated 4 minutes ago`, with a refresh icon button (tooltip *"Refresh repo status"*). It should
also link out to the repository, and show a compact banner when `refreshError` is set — a snapshot
that failed to refresh must not look like a repository with no tags.

### One list of versions, not two

The obvious layout — a tag dropdown for deploying and a separate version dropdown for rolling back —
puts two lists on one page that both look like versions and behave differently. Admins would have to
know which mechanism applies before they can choose, and the two lists overlap: a tag that has been
deployed appears in both.

They are merged into **one list**, because *deploy an existing tag* and *roll back* are the same
intent — put version X live — differing only in whether a built image already exists. The admin
expresses the intent; the platform picks the cheapest correct path.

```
[ + Create & deploy new version ]        ↻ Updated 4m ago

VERSIONS
1.0.7  a3f9c2   ● Current    ● Live
1.0.6  8b21e0   ⚠ Behind main · Built      [ Deploy ~1 min ]
1.0.5  1c4d77   ⚠ Behind main · Not built  [ Deploy ~6 min ]
1.0.2  99ab31   ⚠ Tag deleted · Built      [ Deploy ~1 min ]
```

Each row carries the two facts that decide anything: whether the code is current (its commit equals
the deploy-branch head) and whether an image already exists. The estimated duration is what makes
the underlying difference legible without naming it — "rollback" and "deploy a tag" never appear as
competing concepts.

**The list is a union, not just the tag list.** It is the recent repository tags *plus* every
version this POC has deployed, deduped by tag name. That matters for a version whose tag was later
deleted from GitHub: the image still exists, so it is still deployable, and a tag-only list would
silently drop the exact thing an admin reaches for when the newest release is broken. Those rows are
marked `Tag deleted` — deployable, but not rebuildable.

Consequences worth stating, since this replaces the three separate controls in Requirement 1:

- **One button creates tags** — the only action that writes to GitHub, and the only one disabled by
  missing push access or non-semver tags. The list keeps working in both cases, which is what makes
  those failure states recoverable rather than blocking.
- **`Not built` rows run the full pipeline**, satisfying Requirement 9, and `Built` rows skip
  straight to deploy, satisfying Requirement 8 — but the admin picks a version, not a pipeline.
- **A deliberate rebuild of an already-built tag is not reachable** from this layout. It matters
  only for picking up a new base image from the same source, and is recorded under Future Work
  rather than given a control nobody would find on the day they need it.

**Reuse `version-combobox`.** It already provides type-to-filter, keyboard navigation, outside-click
close and a typed output. Generalise it rather than writing a second dropdown; a per-item
`disabled` + `reason` is the only capability it lacks (needed for both the stale-tag warning and
unavailable rollback images).

**Disabled states carry their reason.** Every disabled control gets a tooltip or inline note: no
push access, image gone, deploy in progress. A disabled button with no explanation is the specific
complaint this redesign exists to fix.

**When tags cannot be created**, the "Deploy new version" button is disabled and accompanied by the
two remedies from requirement 5, written as actions rather than prose — with the second one pointing
directly at the dropdown below it, so the workaround is visible in the same view as the problem.

## Performance

- **One request instead of five** on page open, via `deployment-overview`.
- **The duplicate `getPocById` disappears** — `PocSettingsPage` already has the POC and should pass
  it to the panel as an input rather than each fetching independently.
- **No GitHub call on any page load.** Today `manifest-preview` makes two (branch head, then
  `poc.yaml`) every time the tab is opened *and* after every deploy action.
- **Render before the data arrives.** The page currently shows a bare `Loading…` and the global
  full-screen overlay. The repository header and section headings can render immediately from the
  POC the parent already holds, with the tag/version lists filling in — the same reasoning applied
  to the dashboard's poll, which no longer blanks the grid.
- **Refresh must use `skipLoadingIndicator`**, or the manual refresh flashes the global overlay.
- **Conditional requests to GitHub.** Storing GitHub's `ETag` and sending `If-None-Match` on refresh
  returns `304` without consuming rate limit — worth doing once refresh is on a button an admin can
  hold down.

## Security Considerations

- The snapshot stores no credential: branch names, commit SHAs, tag names and booleans only.
- `can_create_tags` is a **cached authorisation hint for the UI, never an enforcement point**. The
  pipeline re-checks push access at deploy time, because the cached value can be minutes stale and a
  disabled button is not a security control.
- Deploying an existing tag deliberately requires only read access. That is a real widening of who
  can cause a deploy, and it is intended — the tag is created by someone with write access, and the
  platform is only building what git already contains.
- Artifact Registry reads use the app's own ADC identity; no new secret, one new IAM role.

## Open Questions / Future Work

- **"N commits behind"** via the compare API, instead of a binary current/stale flag.
- **Tag protection rules** are still invisible: a token with push access can be refused for a
  protected tag pattern, so "Deploy new version" can be enabled and still fail. Detecting it means
  reimplementing GitHub's pattern matching (see `poc-deployment-pipeline.md`).
- **Two POCs pointing at one repository** will derive the same next tag and collide. Accepted
  deliberately rather than guarded: it is not a supported configuration, and the pre-create re-check
  turns it into a clear failure rather than a wrong deploy.
- **Retention of `poc_repo_tags`** — only the newest few are stored, so a tag that falls out of the
  window can no longer be deployed from the dropdown even though it exists.

## Changelog

- 2026-09-09 — Initial draft. Written after tag collisions blocked deploys for repositories with
  pre-existing tags. Records four decisions taken up front: the next tag name is derived from the
  repository's own tags rather than the platform's numbering; freshness is exact commit equality
  against the deploy branch head; a version whose image is missing from Artifact Registry is
  disabled rather than left to fail mid-deploy; and repository state is refreshed on creation, on
  demand, and after a deploy, with no scheduler.

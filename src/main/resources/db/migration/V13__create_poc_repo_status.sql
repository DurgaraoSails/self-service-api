-- Tag-driven deployment (docs/specs/poc-tag-driven-deployment.md). Makes the repository the single
-- source of truth for a POC's versions instead of the platform inventing a number that can collide
-- with tags the repository already has.

-- One row per POC: the last time its repository state was actually read from GitHub. Written by an
-- explicit refresh (on POC creation, on demand, and after a deploy) — never on a page load, and
-- never expired, since a visibly stale snapshot with a timestamp is safer here than one that
-- silently vanishes mid-session.
CREATE TABLE poc_repo_status (
    poc_id            UUID PRIMARY KEY REFERENCES pocs(id) ON DELETE CASCADE,
    default_branch    TEXT,
    deploy_branch     TEXT,
    head_commit_sha   TEXT,
    can_create_tags   BOOLEAN,
    is_archived       BOOLEAN,
    is_visible        BOOLEAN,
    refreshed_at      TIMESTAMPTZ,
    refresh_error     TEXT
);

-- Child of the snapshot: the newest few tags, as one source of the version list. Rows, not JSON —
-- queryable, and matches how poc_version_containers already stores a version's per-container rows.
-- Only the newest few are kept; storing more invites the question of what "recent" means.
CREATE TABLE poc_repo_tags (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poc_id       UUID NOT NULL REFERENCES pocs(id) ON DELETE CASCADE,
    tag_name     TEXT NOT NULL,
    commit_sha   TEXT NOT NULL,
    is_current   BOOLEAN NOT NULL DEFAULT false,

    -- Preserves GitHub's newest-first order without re-sorting client-side. 0 is newest.
    position     INTEGER NOT NULL,

    CONSTRAINT uq_poc_repo_tags_poc_tag UNIQUE (poc_id, tag_name)
);

CREATE INDEX idx_poc_repo_tags_poc_id ON poc_repo_tags (poc_id);

-- version_label becomes the identity: a tag name, not a number this platform invents. Every
-- existing row's label is already semver and already unique per POC, so both constraints below
-- apply cleanly with no data migration.
ALTER TABLE poc_versions
    DROP CONSTRAINT uq_poc_versions_poc_number,
    DROP CONSTRAINT ck_poc_versions_patch_range,
    ADD CONSTRAINT uq_poc_versions_poc_label UNIQUE (poc_id, version_label);

-- Only populated when version_label parses as semver, and used only for ordering — a repository
-- that tags 'release-2024' or 'latest' has no major/minor/patch to store.
ALTER TABLE poc_versions
    ALTER COLUMN major DROP NOT NULL,
    ALTER COLUMN minor DROP NOT NULL,
    ALTER COLUMN patch DROP NOT NULL;

-- 20 chars fits every version this platform has ever allocated (max "999.999.999"), but a real git
-- tag an admin picks from the repository has no such limit ("release-2024-12-01-hotfix" already
-- exceeds it). Widened to match the tag-name column above, now that a label can be either.
ALTER TABLE poc_versions
    ALTER COLUMN version_label TYPE VARCHAR(255);

-- Whether this version's stored image still resolves in Artifact Registry, so a rollback doesn't
-- fail three minutes into Cloud Build with an obscure "image not found". Checked during refresh,
-- not at read time; null means "never checked" (e.g. a version with no containerImage yet, or a
-- refresh that hasn't run since this column was added).
ALTER TABLE poc_versions
    ADD COLUMN image_available BOOLEAN,
    ADD COLUMN image_checked_at TIMESTAMPTZ;

-- Which pipeline path a BUILD_AND_DEPLOY attempt takes: true creates version_label as a new tag
-- (the platform derived it), false deploys a tag that already exists (an admin picked it from the
-- repository's own tags). A retry must repeat the SAME path — retrying a "deploy an existing tag"
-- attempt as though it were "deploy new version" would demand push access the admin may not have,
-- exactly the contradiction docs/specs/poc-tag-driven-deployment.md's "Deploying an existing tag
-- must not require push access" exists to avoid. Meaningless for REDEPLOY (rollback creates
-- nothing either way); defaulted true so every existing row keeps its actual, only-ever-true
-- history.
ALTER TABLE poc_deployments
    ADD COLUMN create_tag BOOLEAN NOT NULL DEFAULT true;

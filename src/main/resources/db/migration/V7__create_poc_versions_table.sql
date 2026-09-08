CREATE TABLE poc_versions (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poc_id           UUID NOT NULL REFERENCES pocs(id) ON DELETE CASCADE,
    major            INTEGER NOT NULL,
    minor            INTEGER NOT NULL,
    patch            INTEGER NOT NULL,
    version_label    VARCHAR(20) NOT NULL,
    container_image  VARCHAR(500),

    -- The manifest a version was actually built with, stored verbatim so a later
    -- redeploy/rollback never has to re-read poc.yaml from GitHub (which may have changed
    -- since). Null for a repo with no poc.yaml.
    manifest_yaml    TEXT,

    -- The commit a version was actually built from. Comparing this (for the active version)
    -- against pocs.latest_main_commit_sha is the whole of the update check — no GitHub call
    -- needed at read time.
    commit_sha       TEXT,

    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_poc_versions_poc_number UNIQUE (poc_id, major, minor, patch),
    CONSTRAINT ck_poc_versions_patch_range CHECK (patch BETWEEN 1 AND 20)
);

CREATE INDEX idx_poc_versions_poc_id ON poc_versions (poc_id);

-- Closes the circular reference with pocs: poc_versions.poc_id (above) points at pocs, and
-- pocs.active_version_id points back at a poc_versions row, so one side has to be added after
-- both tables exist.
ALTER TABLE pocs ADD COLUMN active_version_id UUID REFERENCES poc_versions(id);

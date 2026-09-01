-- Closes two gaps the versioning model (V13) leaves open, both needed by the external
-- deploy pipeline (poc-deploy-pipeline repo).
--
-- slug: the deploy target's addressable name. A Cloud Run service name and an Artifact
-- Registry image path both need a stable, URL-safe identifier; neither can be derived
-- from a numeric id, and deriving it from `name` breaks the moment a POC is renamed.
ALTER TABLE pocs ADD COLUMN slug TEXT;

ALTER TABLE pocs ADD CONSTRAINT pocs_slug_key UNIQUE (slug);

ALTER TABLE pocs
    ADD CONSTRAINT pocs_slug_format_check
        CHECK (slug IS NULL OR slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$');

-- Upstream tracking: answers "is main ahead of what's deployed?". Written by the pipeline
-- (which holds the GitHub credential), never by self-service-api itself.
ALTER TABLE pocs ADD COLUMN latest_main_commit_sha TEXT;
ALTER TABLE pocs ADD COLUMN latest_main_checked_at TIMESTAMPTZ;

-- The commit a version was actually built from. Comparing this (for the active version)
-- against pocs.latest_main_commit_sha is the whole of the update check — no GitHub call
-- needed at read time.
ALTER TABLE poc_versions ADD COLUMN commit_sha TEXT;

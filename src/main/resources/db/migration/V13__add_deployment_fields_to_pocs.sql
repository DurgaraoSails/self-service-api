-- Deployment-pipeline fields for the self-service Cloud Run pipeline (Part 10).
-- deployment_status is intentionally separate from the existing `status` column:
-- `status` (ACTIVE/HIDDEN) is an admin visibility toggle, unrelated to build/deploy health.
ALTER TABLE pocs
    ADD COLUMN slug                    TEXT,
    ADD COLUMN deployment_status       VARCHAR(50) NOT NULL DEFAULT 'active',
    ADD COLUMN current_release_tag     TEXT,
    ADD COLUMN latest_main_commit_sha  TEXT,
    ADD COLUMN latest_main_checked_at  TIMESTAMPTZ;

-- Default 'active' backfills existing (pre-pipeline) rows and covers any future
-- insert that doesn't set it explicitly, e.g. the existing manual create-POC form.
-- The new pipeline-triggered create path overrides this to 'not_deployed' explicitly.
ALTER TABLE pocs
    ADD CONSTRAINT pocs_deployment_status_check
        CHECK (deployment_status IN ('not_deployed', 'building', 'active', 'failed'));

ALTER TABLE pocs
    ADD CONSTRAINT pocs_slug_key UNIQUE (slug);

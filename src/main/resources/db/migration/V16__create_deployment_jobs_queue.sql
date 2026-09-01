-- Hand-off queue between self-service-api and the external deploy pipeline
-- (poc-deploy-pipeline repo).
--
-- Ownership: self-service-api INSERTs only, and never updates or reads a job's progress —
-- deployment outcomes come back over the existing status webhook, not from this table.
-- The pipeline owns everything after the insert: claiming, retrying, and terminal state.
-- Kept in this repo's migration history only because both services share one database;
-- if the pipeline ever moves to its own database, this table moves with it, unchanged.
CREATE TABLE deployment_jobs (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,

    -- Correlates back to the deployment record the pipeline reports against. The pipeline
    -- posts to /pocs/deployments/{deployment_id}/status when the state changes.
    deployment_id   UUID NOT NULL REFERENCES poc_deployments (id) ON DELETE CASCADE,

    -- Everything the pipeline needs to do the work, captured at enqueue time so it never
    -- has to read self-service-api's domain tables.
    poc_slug        TEXT NOT NULL,
    github_url      TEXT,
    version_label   TEXT NOT NULL,
    container_image TEXT,
    kind            VARCHAR(20) NOT NULL,

    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    attempts        INTEGER NOT NULL DEFAULT 0,
    last_error      TEXT,

    -- Set when a worker claims the row; a claim older than the pipeline's timeout is
    -- treated as abandoned (worker died mid-run) and becomes eligible again.
    claimed_at      TIMESTAMPTZ,
    claimed_by      TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE deployment_jobs
    ADD CONSTRAINT deployment_jobs_kind_check
        CHECK (kind IN ('BUILD_AND_DEPLOY', 'REDEPLOY'));

ALTER TABLE deployment_jobs
    ADD CONSTRAINT deployment_jobs_status_check
        CHECK (status IN ('PENDING', 'CLAIMED', 'DONE', 'FAILED'));

-- REDEPLOY reuses an already-built image and needs no repo; BUILD_AND_DEPLOY needs the
-- repo and has no image yet. Enforced here so a malformed job can't reach the pipeline.
ALTER TABLE deployment_jobs
    ADD CONSTRAINT deployment_jobs_kind_inputs_check
        CHECK (
            (kind = 'BUILD_AND_DEPLOY' AND github_url IS NOT NULL)
            OR (kind = 'REDEPLOY' AND container_image IS NOT NULL)
        );

-- The claim query's only index: partial, so it stays small no matter how much
-- terminal history accumulates.
CREATE INDEX deployment_jobs_claimable_idx
    ON deployment_jobs (created_at)
    WHERE status IN ('PENDING', 'CLAIMED');

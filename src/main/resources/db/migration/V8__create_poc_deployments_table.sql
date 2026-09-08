CREATE TABLE poc_deployments (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    poc_id              UUID NOT NULL REFERENCES pocs(id) ON DELETE CASCADE,
    poc_version_id      UUID NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE,
    kind                VARCHAR(20) NOT NULL,
    status              VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    logs_url            VARCHAR(500),
    error_message       VARCHAR(2000),
    initiated_by        VARCHAR(36) REFERENCES users(id),

    -- Static per-container progress for one deployment attempt (name/role/image/port/state), so
    -- the admin can see which containers a multi-container deploy is building without any
    -- log-streaming infrastructure. Transient/attempt-scoped, unlike poc_version_containers,
    -- which is the durable per-version record — hence a column here rather than another table.
    container_progress  TEXT,

    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_poc_deployments_poc_id_started_at ON poc_deployments (poc_id, started_at DESC);
CREATE INDEX idx_poc_deployments_poc_version_id ON poc_deployments (poc_version_id);

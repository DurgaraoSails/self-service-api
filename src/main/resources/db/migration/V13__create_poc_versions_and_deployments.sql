CREATE TABLE poc_versions (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poc_id           BIGINT NOT NULL REFERENCES pocs(id) ON DELETE CASCADE,
    major            INTEGER NOT NULL,
    minor            INTEGER NOT NULL,
    patch            INTEGER NOT NULL,
    version_label    VARCHAR(20) NOT NULL,
    container_image  VARCHAR(500),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_poc_versions_poc_number UNIQUE (poc_id, major, minor, patch),
    CONSTRAINT ck_poc_versions_patch_range CHECK (patch BETWEEN 1 AND 20)
);
CREATE INDEX idx_poc_versions_poc_id ON poc_versions (poc_id);

CREATE TABLE poc_deployments (
    id               UUID PRIMARY KEY,
    poc_id           BIGINT NOT NULL REFERENCES pocs(id) ON DELETE CASCADE,
    poc_version_id   BIGINT NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE,
    kind             VARCHAR(20) NOT NULL,
    status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    logs_url         VARCHAR(500),
    error_message    VARCHAR(2000),
    initiated_by     VARCHAR(36) REFERENCES users(id),
    started_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_poc_deployments_poc_id_started_at ON poc_deployments (poc_id, started_at DESC);
CREATE INDEX idx_poc_deployments_poc_version_id ON poc_deployments (poc_version_id);

ALTER TABLE pocs ADD COLUMN active_version_id BIGINT REFERENCES poc_versions(id);

ALTER TABLE pocs
    DROP COLUMN version,
    DROP COLUMN container_image;

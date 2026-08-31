-- One row per tag-build-deploy attempt for a POC (Part 10).
CREATE TABLE poc_deployments (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poc_id          BIGINT NOT NULL REFERENCES pocs (id),
    release_tag     TEXT NOT NULL,
    cloud_build_id  TEXT,
    image_uri       TEXT,
    status          VARCHAR(50) NOT NULL DEFAULT 'building',
    failure_reason  TEXT,
    triggered_by    TEXT,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE poc_deployments
    ADD CONSTRAINT poc_deployments_status_check
        CHECK (status IN ('building', 'active', 'failed'));

CREATE INDEX poc_deployments_poc_id_idx ON poc_deployments (poc_id);

-- Narrow index for the scheduled poller's "find in-flight builds" query (T10.7).
CREATE INDEX poc_deployments_in_flight_idx ON poc_deployments (status) WHERE status = 'building';

-- Manifest-driven multi-container deployment: a POC repo may declare an optional poc.yaml at its
-- root with one ingress container plus any number of sidecars, all deployed as one Cloud Run
-- service. Purely additive — a repo with no poc.yaml keeps deploying exactly as before, and every
-- pre-existing version simply has zero rows in the new table below.

-- The manifest a version was actually built with, stored verbatim so a later redeploy/rollback
-- never has to re-read poc.yaml from GitHub (which may have changed since). Null for a repo with
-- no poc.yaml.
ALTER TABLE poc_versions ADD COLUMN manifest_yaml TEXT;

-- Static per-container progress for one deployment attempt (name/role/image/port/state), so the
-- admin can see which containers a multi-container deploy is building without any log-streaming
-- infrastructure. Transient/attempt-scoped, unlike poc_version_containers below, which is the
-- durable per-version record — hence a column here rather than another table.
ALTER TABLE poc_deployments ADD COLUMN container_progress TEXT;

-- One row per container a version actually built. A version with none (every version built before
-- this feature existed, or any version whose repo simply has no poc.yaml) falls back to the
-- pre-existing single containerImage under the synthesized default's ingress name ("app").
CREATE TABLE poc_version_containers (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poc_version_id   BIGINT NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE,
    name             VARCHAR(40) NOT NULL,
    role             VARCHAR(16) NOT NULL,
    container_image  VARCHAR(500),
    port             INTEGER,
    CONSTRAINT ck_pvc_role CHECK (role IN ('INGRESS', 'SIDECAR')),
    CONSTRAINT uq_pvc_version_name UNIQUE (poc_version_id, name)
);

-- Enforced in ManifestValidator before any build starts, and again here at the data level so the
-- invariant holds regardless of what wrote the row.
CREATE UNIQUE INDEX uq_pvc_one_ingress_per_version
    ON poc_version_containers (poc_version_id) WHERE role = 'INGRESS';

CREATE INDEX idx_pvc_poc_version_id ON poc_version_containers (poc_version_id);

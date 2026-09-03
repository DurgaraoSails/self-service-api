-- Records each container declared in a version's poc.yaml (or the synthesized single-container
-- default when a repo has none). poc_versions.container_image/commit_sha keep meaning exactly
-- what they mean today — the INGRESS container's image and the repo's commit — this table is
-- purely additive, populated going forward only; existing versions simply have no rows here.
CREATE TABLE poc_version_containers (
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poc_version_id  BIGINT NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE,
    name            VARCHAR(40) NOT NULL,
    role            VARCHAR(16) NOT NULL,
    container_image TEXT,
    port            INTEGER,
    CONSTRAINT uq_pvc_version_name UNIQUE (poc_version_id, name),
    CONSTRAINT ck_pvc_role CHECK (role IN ('INGRESS','SIDECAR'))
);

-- Enforced in the database, not just in ManifestValidator — a version can never end up recorded
-- with zero or multiple ingress containers, regardless of what wrote the row.
CREATE UNIQUE INDEX uq_pvc_one_ingress_per_version
    ON poc_version_containers (poc_version_id) WHERE role = 'INGRESS';

CREATE INDEX idx_pvc_version ON poc_version_containers (poc_version_id);

-- The manifest exactly as it was at build time (or NULL when the repo had none — resolved back to
-- the same synthesized single-container default). This is what redeploy reads, never a fresh
-- GitHub fetch — reconstructs exactly what was deployed then, regardless of what poc.yaml says in
-- the repo today.
ALTER TABLE poc_versions ADD COLUMN manifest_yaml TEXT;

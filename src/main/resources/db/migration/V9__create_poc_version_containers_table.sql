-- Manifest-driven multi-container deployment: a POC repo may declare an optional poc.yaml at its
-- root with one ingress container plus any number of sidecars, all deployed as one Cloud Run
-- service. Purely additive — a repo with no poc.yaml keeps deploying exactly as before, and every
-- such version simply has zero rows here, falling back to the pre-existing single containerImage
-- under the synthesized default ingress name ("app").
CREATE TABLE poc_version_containers (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    poc_version_id   UUID NOT NULL REFERENCES poc_versions(id) ON DELETE CASCADE,
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

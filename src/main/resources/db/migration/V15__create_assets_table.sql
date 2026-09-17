CREATE TABLE assets (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_type              VARCHAR(32) NOT NULL,
    owner_user_id           VARCHAR(36) NOT NULL REFERENCES users(id),
    submitted_by_user_id    VARCHAR(36) NOT NULL REFERENCES users(id),

    -- Optional link to an existing hosted POC. Launch availability is always derived from the
    -- linked POC's own readiness at read time — never stored here as a second launch URL.
    poc_id                  UUID REFERENCES pocs(id),

    -- Both nullable: a brand new asset has a working revision but no approved one yet. FKs to
    -- asset_revisions are added by V17, once that table (and its (id, asset_id) unique pair) exists.
    approved_revision_id    UUID,
    working_revision_id     UUID,

    archived_at             TIMESTAMPTZ,
    archived_by_user_id     VARCHAR(36) REFERENCES users(id),

    -- JPA optimistic-concurrency version. Every mutation that can race carries an expectedVersion.
    version                 BIGINT NOT NULL DEFAULT 0,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_assets_asset_type
        CHECK (asset_type IN ('AI_USE_CASE', 'POC', 'BLOG', 'ARTICLE', 'HACKATHON_IDEA', 'DOCUMENT')),

    -- poc_id is only meaningful for the POC asset type; every other type must leave it null.
    CONSTRAINT ck_assets_poc_id_only_for_poc_type
        CHECK (poc_id IS NULL OR asset_type = 'POC')
);

CREATE INDEX idx_assets_owner_user_id ON assets (owner_user_id);
CREATE INDEX idx_assets_submitted_by_user_id ON assets (submitted_by_user_id);
CREATE INDEX idx_assets_asset_type ON assets (asset_type);
CREATE INDEX idx_assets_poc_id ON assets (poc_id) WHERE poc_id IS NOT NULL;

-- Every discovery/listing query filters to non-archived assets; a partial index keeps that cheap
-- as archived rows accumulate instead of scanning them on every request.
CREATE INDEX idx_assets_active ON assets (id) WHERE archived_at IS NULL;

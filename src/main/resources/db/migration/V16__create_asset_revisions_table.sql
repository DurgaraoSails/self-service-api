CREATE TABLE asset_revisions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id                UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    revision_number         INTEGER NOT NULL,
    state                   VARCHAR(32) NOT NULL,

    title                   VARCHAR(200) NOT NULL,
    summary                 TEXT NOT NULL,
    problem_statement       TEXT,
    business_impact         TEXT,
    solution_overview       TEXT,
    source_url              VARCHAR(2048) NOT NULL,

    authored_by_user_id     VARCHAR(36) NOT NULL REFERENCES users(id),
    submitted_at            TIMESTAMPTZ,

    version                 BIGINT NOT NULL DEFAULT 0,

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_asset_revisions_state
        CHECK (state IN ('DRAFT', 'PENDING_REVIEW', 'CHANGES_REQUESTED', 'APPROVED', 'REJECTED', 'SUPERSEDED')),
    CONSTRAINT ck_asset_revisions_number_positive
        CHECK (revision_number > 0),

    CONSTRAINT uq_asset_revisions_asset_number UNIQUE (asset_id, revision_number),

    -- Exists only so assets.approved_revision_id/working_revision_id can point at a composite
    -- (revision_id, asset_id) pair in V17 — the database-level guarantee that neither pointer can
    -- ever reference another asset's revision.
    CONSTRAINT uq_asset_revisions_id_asset UNIQUE (id, asset_id)
);

CREATE INDEX idx_asset_revisions_asset_id ON asset_revisions (asset_id);
CREATE INDEX idx_asset_revisions_state ON asset_revisions (state);

-- Reviewer-queue ordering: oldest PENDING_REVIEW submission first.
CREATE INDEX idx_asset_revisions_pending_submitted_at
    ON asset_revisions (submitted_at) WHERE state = 'PENDING_REVIEW';

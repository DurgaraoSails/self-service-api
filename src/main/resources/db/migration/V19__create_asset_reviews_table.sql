-- Append-only: a decision is never edited once recorded. No updated_at on purpose.
CREATE TABLE asset_reviews (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    revision_id         UUID NOT NULL REFERENCES asset_revisions(id),
    reviewer_user_id    VARCHAR(36) NOT NULL REFERENCES users(id),
    decision            VARCHAR(20) NOT NULL,
    feedback            TEXT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_asset_reviews_decision
        CHECK (decision IN ('APPROVE', 'REQUEST_CHANGES', 'REJECT'))
);

CREATE INDEX idx_asset_reviews_revision_id ON asset_reviews (revision_id);

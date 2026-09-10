-- One current row per (asset, user): PUT /assets/{assetId}/feedback idempotently replaces the
-- caller's feedback, which only makes sense against a single row, not an append-only log.
CREATE TABLE asset_feedback (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id        UUID NOT NULL REFERENCES assets(id) ON DELETE CASCADE,
    user_id         VARCHAR(36) NOT NULL REFERENCES users(id),
    rating          VARCHAR(20) NOT NULL,
    comment         VARCHAR(2000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_asset_feedback_rating
        CHECK (rating IN ('HELPFUL', 'NOT_HELPFUL')),
    CONSTRAINT uq_asset_feedback_asset_user UNIQUE (asset_id, user_id)
);

CREATE INDEX idx_asset_feedback_asset_id ON asset_feedback (asset_id);

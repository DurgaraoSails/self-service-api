CREATE TABLE tags (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                VARCHAR(80) NOT NULL,
    normalized_name     VARCHAR(80) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset_revision_tags (
    revision_id     UUID NOT NULL REFERENCES asset_revisions(id) ON DELETE CASCADE,

    -- No cascade here on purpose: a tag is shared across revisions, so deleting one should not
    -- silently delete unrelated revisions' links to it.
    tag_id          BIGINT NOT NULL REFERENCES tags(id),

    PRIMARY KEY (revision_id, tag_id)
);

CREATE INDEX idx_asset_revision_tags_tag_id ON asset_revision_tags (tag_id);

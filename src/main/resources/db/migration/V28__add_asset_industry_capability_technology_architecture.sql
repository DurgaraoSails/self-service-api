-- Industry: admin-curated, flexible lookup table (mirrors poc_categories).
CREATE TABLE industries (
    id    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name  VARCHAR(100) NOT NULL UNIQUE
);

INSERT INTO industries (name) VALUES
    ('Healthcare & Life Sciences'),
    ('Banking & Financial Services'),
    ('Retail & E-commerce'),
    ('Talent & HR'),
    ('Others');

ALTER TABLE asset_revisions ADD COLUMN industry_id BIGINT REFERENCES industries(id);
ALTER TABLE asset_revisions ADD COLUMN ai_architecture VARCHAR(200);

CREATE INDEX idx_asset_revisions_industry_id ON asset_revisions (industry_id) WHERE industry_id IS NOT NULL;

-- AI capability: free-text, user-created, multi-valued (mirrors tags/asset_revision_tags).
CREATE TABLE ai_capabilities (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                VARCHAR(80) NOT NULL,
    normalized_name     VARCHAR(80) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset_revision_ai_capabilities (
    revision_id         UUID NOT NULL REFERENCES asset_revisions(id) ON DELETE CASCADE,

    -- No cascade here on purpose: a capability is shared across revisions, same reasoning as tags.
    ai_capability_id     BIGINT NOT NULL REFERENCES ai_capabilities(id),

    PRIMARY KEY (revision_id, ai_capability_id)
);

CREATE INDEX idx_asset_revision_ai_capabilities_capability_id ON asset_revision_ai_capabilities (ai_capability_id);

-- Technologies: free-text, user-created, multi-valued (mirrors tags/asset_revision_tags).
CREATE TABLE technologies (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                VARCHAR(80) NOT NULL,
    normalized_name     VARCHAR(80) NOT NULL UNIQUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE asset_revision_technologies (
    revision_id     UUID NOT NULL REFERENCES asset_revisions(id) ON DELETE CASCADE,

    -- No cascade here on purpose: a technology is shared across revisions, same reasoning as tags.
    technology_id   BIGINT NOT NULL REFERENCES technologies(id),

    PRIMARY KEY (revision_id, technology_id)
);

CREATE INDEX idx_asset_revision_technologies_technology_id ON asset_revision_technologies (technology_id);

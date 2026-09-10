CREATE TABLE asset_ai_suggestions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    revision_id             UUID NOT NULL REFERENCES asset_revisions(id) ON DELETE CASCADE,
    status                  VARCHAR(20) NOT NULL,

    suggested_title         VARCHAR(200),
    suggested_summary       TEXT,
    -- JSON array of strings, serialized the same way PocDeployment.container_progress is: a plain
    -- TEXT column, serialized/deserialized by an injected ObjectMapper at the mapper layer.
    suggested_tags          TEXT,

    provider                VARCHAR(100),
    model                   VARCHAR(100),
    schema_version          VARCHAR(20),

    -- SHA-256 hex of the canonical suggestion input, so a result can be looked up and reused for
    -- an unchanged draft instead of re-calling the provider.
    input_checksum          VARCHAR(64),

    error_code              VARCHAR(100),

    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_asset_ai_suggestions_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX idx_asset_ai_suggestions_revision_id ON asset_ai_suggestions (revision_id);

-- Lookup key for a reusable prior result. Deliberately NOT a unique constraint: a FAILED run must
-- stay retriable under the same (revision, checksum, provider, model, schema_version) tuple.
CREATE INDEX idx_asset_ai_suggestions_dedup
    ON asset_ai_suggestions (revision_id, input_checksum, provider, model, schema_version);

-- Keyword search must work on its own (ASSET_AI_ENABLED=false, ASSET_SEMANTIC_SEARCH_ENABLED=false
-- is a supported configuration), so this migration creates the full lexical side of the table now.
-- The pgvector embedding column is deliberately NOT here — it lands in a separate Phase 3 migration
-- once the local/production PostgreSQL image is swapped for a pgvector-enabled one, so the vertical
-- slice can ship against the existing plain postgres:17 image. See docs/specs/asset-hub.md's
-- 2026-09-10 changelog entry for the full reasoning.
CREATE TABLE asset_search_documents (
    asset_id                UUID PRIMARY KEY REFERENCES assets(id) ON DELETE CASCADE,
    revision_id             UUID NOT NULL UNIQUE REFERENCES asset_revisions(id),

    -- Raw normalized text the vector below was built from — kept for debugging/inspection, not
    -- itself searched directly.
    search_text             TEXT NOT NULL,

    -- Populated explicitly by application code (title+tags weight A, summary B, problem/impact/
    -- solution C, owner display name D — see the Search Contract), not a Postgres GENERATED column:
    -- the per-field weighting can't be expressed as a single generated expression over one column.
    search_vector           TSVECTOR NOT NULL,

    -- Populated only once Phase 3 semantic search is enabled; null until then.
    embedding_provider      VARCHAR(100),
    embedding_model         VARCHAR(100),
    embedding_dimensions    INTEGER,
    embedding_checksum      VARCHAR(64),

    indexed_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_asset_search_documents_search_vector
    ON asset_search_documents USING GIN (search_vector);

-- Phase 3 semantic search: the pgvector extension and the embedding column deliberately held back
-- from V21 until the local/production PostgreSQL image carries pgvector, so that the Phase 0/1
-- vertical slice could ship with working keyword search against a stock image. See
-- docs/specs/asset-hub.md's "PostgreSQL-backed hybrid search for the POC".
--
-- This migration REQUIRES a pgvector-enabled PostgreSQL. A stock postgres:17 image (or a default
-- Windows/apt PostgreSQL install) does not ship the extension, and CREATE EXTENSION below will
-- fail with "could not open extension control file". Use pgvector/pgvector:pg17 — infra/
-- selfservice_db/compose.yaml is set to it — or install the extension into the existing server.
CREATE EXTENSION IF NOT EXISTS vector;

-- 1024 dimensions is voyage-4's configured output size (AssetHubProperties.Embedding.dimensions).
-- The width is part of the column type, so changing model or dimensions is a new migration plus a
-- full reindex, not a config edit: AssetEmbeddingClientConfig refuses to start when the configured
-- dimensions disagree with this column, rather than letting every insert fail at runtime.
ALTER TABLE asset_search_documents
    ADD COLUMN embedding vector(1024);

-- HNSW rather than IVFFlat: it needs no training step and no rebuild as rows accumulate, which
-- suits a catalog that grows a few approved revisions at a time. vector_cosine_ops matches the
-- `<=>` cosine-distance operator AssetSearchRepository.findSemanticCandidateIds orders by — an
-- index built for a different operator class would simply never be used. Rows with a NULL
-- embedding (every asset approved before semantic search was enabled, and every asset whose
-- embedding call failed) are skipped by the index and excluded by that query's
-- `sd.embedding is not null` guard.
CREATE INDEX idx_asset_search_documents_embedding
    ON asset_search_documents USING hnsw (embedding vector_cosine_ops);

package com.sails.ai.selfserviceapi.asset.ai;

/**
 * Boundary between Asset Hub's search indexer and whichever embedding model is configured. A bean
 * of this type is only registered when {@code asset-hub.semantic-search-enabled=true} (see
 * {@code asset/config/AssetEmbeddingClientConfig}); its absence is how indexing and search degrade
 * to lexical-only, with no provider-specific branching elsewhere. See docs/specs/asset-hub.md's
 * "Semantic search embeddings: Voyage AI" for why this is currently unreachable in every real
 * deployment (no pgvector column to store the result in yet, no API key configured).
 */
public interface EmbeddingProvider {

    /** Recorded on {@code asset_search_documents.embedding_provider}, e.g. "voyage". */
    String providerName();

    /** Recorded on {@code asset_search_documents.embedding_model}. */
    String modelName();

    /** Recorded on {@code asset_search_documents.embedding_dimensions}; fixed per model. */
    int dimensions();

    /**
     * Embeds {@code text} into a {@link #dimensions()}-length vector. Throws
     * {@link EmbeddingProviderException} on timeout, transport failure, or a response that does not
     * match the expected dimension; never returns a partial or wrong-length vector.
     */
    float[] embed(String text);
}

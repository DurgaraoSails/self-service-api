package com.sails.ai.selfserviceapi.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Independent feature flags. Keyword search and manual review must work with both aiEnabled and
 * semanticSearchEnabled false — that combination is the safe default for every existing deployment.
 */
@ConfigurationProperties(prefix = "asset-hub")
public record AssetHubProperties(
        boolean enabled,
        boolean aiEnabled,
        boolean semanticSearchEnabled,
        Ai ai,
        Embedding embedding
) {

    /**
     * Vertex AI Gemini config for {@code GeminiAssetAiProvider}, bound only when read — a missing
     * or blank projectId is fine while aiEnabled is false, since no bean ever calls {@link #ai()}
     * in that case (see AssetAiClientConfig / GeminiAssetAiProvider's ConditionalOnProperty).
     */
    public record Ai(
            String projectId,
            String region,
            @DefaultValue("gemini-2.5-flash") String model,
            @DefaultValue("20") int timeoutSeconds
    ) {
    }

    /**
     * Voyage AI config for {@code VoyageEmbeddingProvider}, bound only when read — a missing/blank
     * apiKey is fine while semanticSearchEnabled is false, since no bean ever calls
     * {@link #embedding()} in that case (see AssetEmbeddingClientConfig /
     * VoyageEmbeddingProvider's ConditionalOnProperty). Dimensions must match the fixed dimension
     * of {@code model} — see docs/specs/asset-hub.md's "Semantic search embeddings" decision.
     */
    public record Embedding(
            String apiKey,
            @DefaultValue("https://api.voyageai.com") String baseUrl,
            @DefaultValue("voyage-4") String model,
            @DefaultValue("1024") int dimensions,
            @DefaultValue("20") int timeoutSeconds
    ) {
    }
}

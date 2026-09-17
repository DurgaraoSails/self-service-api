package com.sails.ai.selfserviceapi.asset.config;

import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Voyage AI REST client for {@link com.sails.ai.selfserviceapi.asset.ai.VoyageEmbeddingProvider}.
 * Voyage authenticates with a plain bearer API key (no ADC/service-account flow like Vertex AI),
 * so this is simpler than {@code AssetAiClientConfig}: no lazy credential resolution needed, just
 * a static bearer header built from config that is only ever read when semantic search is enabled.
 */
@Configuration
@ConditionalOnProperty(prefix = "asset-hub", name = "semantic-search-enabled", havingValue = "true")
public class AssetEmbeddingClientConfig {

    /**
     * Width of {@code asset_search_documents.embedding} as declared in {@code V26}. pgvector makes
     * the dimension part of the column type, so a provider configured for any other width cannot
     * store a row at all — checked here so that fails at startup naming the mismatch, rather than
     * as an opaque "expected 1024 dimensions, not N" on every approval.
     */
    private static final int EMBEDDING_COLUMN_DIMENSIONS = 1024;

    @Bean
    public RestClient voyageRestClient(AssetHubProperties properties) {
        validate(properties.embedding());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(properties.embedding().timeoutSeconds()).toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        return RestClient.builder()
                .baseUrl(properties.embedding().baseUrl())
                .requestFactory(requestFactory)
                .defaultHeader("Authorization", "Bearer " + properties.embedding().apiKey())
                .build();
    }

    private static void validate(AssetHubProperties.Embedding embedding) {
        if (embedding.apiKey() == null || embedding.apiKey().isBlank()) {
            throw new IllegalStateException(
                    "asset-hub.embedding.api-key is required when asset-hub.semantic-search-enabled=true. "
                            + "Set ASSET_HUB_EMBEDDING_API_KEY, or set asset-hub.semantic-search-enabled=false "
                            + "to run keyword-only search.");
        }
        if (embedding.model() == null || embedding.model().isBlank()) {
            throw new IllegalStateException(
                    "asset-hub.embedding.model must not be blank. Set ASSET_HUB_EMBEDDING_MODEL.");
        }
        if (embedding.dimensions() != EMBEDDING_COLUMN_DIMENSIONS) {
            throw new IllegalStateException(
                    "asset-hub.embedding.dimensions is %d but asset_search_documents.embedding is vector(%d) (migration V26). "
                            .formatted(embedding.dimensions(), EMBEDDING_COLUMN_DIMENSIONS)
                            + "Changing the embedding width needs a new migration altering that column and a full "
                            + "reindex of every approved asset, not just a configuration change.");
        }
    }
}

package com.sails.ai.selfserviceapi.asset.ai;

import com.sails.ai.selfserviceapi.asset.config.AssetHubProperties;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Calls Voyage AI's {@code /v1/embeddings} REST endpoint. See docs/specs/asset-hub.md's "Semantic
 * search embeddings: Voyage AI" — this provider has no pgvector column to write its result into
 * yet and no API key configured anywhere, so it cannot be exercised end-to-end until both exist;
 * it exists now so that gap is the only thing blocking semantic search, not missing code.
 */
@Component
@ConditionalOnProperty(prefix = "asset-hub", name = "semantic-search-enabled", havingValue = "true")
public class VoyageEmbeddingProvider implements EmbeddingProvider {

    private static final String PROVIDER_NAME = "voyage";

    private final RestClient voyageRestClient;
    private final AssetHubProperties properties;

    public VoyageEmbeddingProvider(@Qualifier("voyageRestClient") RestClient voyageRestClient,
                                    AssetHubProperties properties) {
        this.voyageRestClient = voyageRestClient;
        this.properties = properties;
    }

    @Override
    public String providerName() {
        return PROVIDER_NAME;
    }

    @Override
    public String modelName() {
        return properties.embedding().model();
    }

    @Override
    public int dimensions() {
        return properties.embedding().dimensions();
    }

    @Override
    public float[] embed(String text) {
        EmbeddingsResponse response = call(text);
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new EmbeddingProviderException("INVALID_PROVIDER_RESPONSE", "Voyage returned no embeddings");
        }
        float[] vector = response.data().get(0).embedding();
        if (vector == null || vector.length != dimensions()) {
            throw new EmbeddingProviderException("INVALID_PROVIDER_RESPONSE",
                    "Voyage embedding length did not match the configured dimension");
        }
        return vector;
    }

    private EmbeddingsResponse call(String text) {
        EmbeddingsRequest request = new EmbeddingsRequest(List.of(text), properties.embedding().model());
        try {
            return voyageRestClient.post()
                    .uri("/v1/embeddings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(EmbeddingsResponse.class);
        } catch (ResourceAccessException e) {
            throw new EmbeddingProviderException("PROVIDER_TIMEOUT", "Voyage request timed out or was unreachable", e);
        } catch (RestClientResponseException e) {
            throw new EmbeddingProviderException("PROVIDER_ERROR",
                    "Voyage request failed with status " + e.getStatusCode().value(), e);
        }
    }

    // -- Voyage /v1/embeddings wire shapes ------------------------------------------------------

    private record EmbeddingsRequest(List<String> input, String model) {
    }

    private record EmbeddingsResponse(List<EmbeddingDatum> data) {
    }

    private record EmbeddingDatum(float[] embedding) {
    }
}

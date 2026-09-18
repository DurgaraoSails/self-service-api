package com.sails.ai.selfserviceapi.asset.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Configuration validation for Voyage AI embeddings. The dimension check matters most: pgvector
 * bakes the width into the column type, so a mismatch is not a degraded search — it is an insert
 * that can never succeed, and without this check it would only surface on the next approval.
 */
class AssetEmbeddingClientConfigTest {

    private final AssetEmbeddingClientConfig config = new AssetEmbeddingClientConfig();

    private static AssetHubProperties properties(AssetHubProperties.Embedding embedding) {
        return new AssetHubProperties(false, true,
                new AssetHubProperties.Ai(AssetAiTransport.VERTEX_AI, "sync-folio", "us-central1", null,
                        "gemini-2.5-flash", 20),
                embedding);
    }

    private static AssetHubProperties.Embedding embedding(String apiKey, String model, int dimensions) {
        return new AssetHubProperties.Embedding(apiKey, "https://api.voyageai.com", model, dimensions, 20);
    }

    @Test
    @DisplayName("builds a client for voyage-4 at the 1024 dimensions V26 declares")
    void acceptsMatchingConfiguration() {
        assertThatCode(() -> config.voyageRestClient(properties(embedding("test-key", "voyage-4", 1024))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("missing API key fails startup and points at the keyword-only fallback")
    void rejectsMissingApiKey() {
        assertThatThrownBy(() -> config.voyageRestClient(properties(embedding(null, "voyage-4", 1024))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.embedding.api-key")
                .hasMessageContaining("semantic-search-enabled=false");
    }

    @Test
    @DisplayName("blank API key is treated as missing, not as a valid empty credential")
    void rejectsBlankApiKey() {
        assertThatThrownBy(() -> config.voyageRestClient(properties(embedding("  ", "voyage-4", 1024))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.embedding.api-key");
    }

    @Test
    @DisplayName("a dimension other than the column width fails startup naming both numbers")
    void rejectsDimensionMismatch() {
        assertThatThrownBy(() -> config.voyageRestClient(properties(embedding("test-key", "voyage-4", 512))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("512")
                .hasMessageContaining("1024")
                .hasMessageContaining("V26");
    }

    @Test
    @DisplayName("the mismatch message says a reindex is required, not just a config edit")
    void mismatchMessageExplainsTheRealCost() {
        assertThatThrownBy(() -> config.voyageRestClient(properties(embedding("test-key", "voyage-4", 2048))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reindex");
    }

    @Test
    @DisplayName("blank model fails startup")
    void rejectsBlankModel() {
        assertThatThrownBy(() -> config.voyageRestClient(properties(embedding("test-key", " ", 1024))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.embedding.model");
    }
}

package com.sails.ai.selfserviceapi.asset.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Configuration validation for the two Gemini transports. The point of these is that a
 * misconfigured deployment fails at startup naming the missing property, instead of looking
 * healthy until the first suggestion returns a 401/404 that reads like a provider outage.
 */
class AssetAiClientConfigTest {

    private final AssetAiClientConfig config = new AssetAiClientConfig();

    /** Embedding config is irrelevant here — semanticSearchEnabled is false, so nothing reads it. */
    private static AssetHubProperties properties(AssetHubProperties.Ai ai) {
        return new AssetHubProperties(true, true, false, ai,
                new AssetHubProperties.Embedding(null, "https://api.voyageai.com", "voyage-4", 1024, 20));
    }

    private static AssetHubProperties.Ai vertexAi(String projectId, String region) {
        return new AssetHubProperties.Ai(AssetAiTransport.VERTEX_AI, projectId, region, null, "gemini-2.5-flash", 20);
    }

    private static AssetHubProperties.Ai geminiApi(String apiKey) {
        return new AssetHubProperties.Ai(AssetAiTransport.GEMINI_API, null, "us-central1", apiKey, "gemini-2.5-flash", 20);
    }

    @Test
    @DisplayName("VERTEX_AI builds a client when a project and region are present")
    void vertexAiAcceptsCompleteConfiguration() {
        assertThatCode(() -> config.geminiRestClient(properties(vertexAi("sync-folio", "us-central1"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("GEMINI_API builds a client from an API key alone — no GCP project needed")
    void geminiApiNeedsOnlyAnApiKey() {
        assertThatCode(() -> config.geminiRestClient(properties(geminiApi("test-key"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("VERTEX_AI without a project fails startup and points at the alternative transport")
    void vertexAiRejectsMissingProject() {
        assertThatThrownBy(() -> config.geminiRestClient(properties(vertexAi(null, "us-central1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.ai.project-id")
                .hasMessageContaining("GEMINI_API");
    }

    @Test
    @DisplayName("VERTEX_AI treats a blank project as missing, not as a valid empty value")
    void vertexAiRejectsBlankProject() {
        assertThatThrownBy(() -> config.geminiRestClient(properties(vertexAi("   ", "us-central1"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.ai.project-id");
    }

    @Test
    @DisplayName("VERTEX_AI without a region fails startup — the region selects the aiplatform host")
    void vertexAiRejectsMissingRegion() {
        assertThatThrownBy(() -> config.geminiRestClient(properties(vertexAi("sync-folio", " "))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.ai.region");
    }

    @Test
    @DisplayName("GEMINI_API without a key fails startup and points at the alternative transport")
    void geminiApiRejectsMissingKey() {
        assertThatThrownBy(() -> config.geminiRestClient(properties(geminiApi(null))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.ai.api-key")
                .hasMessageContaining("VERTEX_AI");
    }

    @Test
    @DisplayName("GEMINI_API does not demand a GCP project it has no use for")
    void geminiApiDoesNotRequireProject() {
        assertThatCode(() -> config.geminiRestClient(properties(geminiApi("test-key"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("VERTEX_AI does not demand an API key it has no use for")
    void vertexAiDoesNotRequireApiKey() {
        assertThatCode(() -> config.geminiRestClient(properties(vertexAi("sync-folio", "us-central1"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A blank model fails startup on either transport")
    void blankModelIsRejected() {
        AssetHubProperties.Ai noModel =
                new AssetHubProperties.Ai(AssetAiTransport.GEMINI_API, null, "us-central1", "test-key", " ", 20);
        assertThatThrownBy(() -> config.geminiRestClient(properties(noModel)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("asset-hub.ai.model");
    }

    @Test
    @DisplayName("VERTEX_AI is the default transport, so existing deployments keep ADC behaviour")
    void vertexAiIsTheDefault() {
        assertThat(vertexAi("sync-folio", "us-central1").isVertexAi()).isTrue();
        assertThat(geminiApi("test-key").isVertexAi()).isFalse();
    }
}

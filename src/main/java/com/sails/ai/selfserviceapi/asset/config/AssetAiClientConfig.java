package com.sails.ai.selfserviceapi.asset.config;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The Gemini REST client for {@link com.sails.ai.selfserviceapi.asset.ai.GeminiAssetAiProvider},
 * built for whichever {@link AssetAiTransport} is configured. Only the host and the credential
 * differ between the two; the request body is identical, so the provider stays transport-agnostic
 * and reads only the path from configuration.
 *
 * <p>Both clients use {@link SimpleClientHttpRequestFactory} rather than Spring's default JDK
 * HttpClient, for the same reason deploypipeline.config.PipelineRestClientConfig documents: the
 * latter opens a loopback socket for its async selector, which fails outright in some sandboxed and
 * corporate-network environments.
 */
@Configuration
@ConditionalOnProperty(prefix = "asset-hub", name = "ai-enabled", havingValue = "true")
public class AssetAiClientConfig {

    @Bean
    public RestClient geminiRestClient(AssetHubProperties properties) {
        AssetHubProperties.Ai ai = properties.ai();
        validate(ai);
        return ai.isVertexAi() ? vertexAiClient(ai) : geminiApiClient(ai);
    }

    /**
     * Fails startup naming the missing property, rather than letting the first suggestion attempt
     * fail with a 401/404 that looks like a provider outage. Only the selected transport's fields
     * are checked: a Vertex AI deployment has no API key and should not be asked for one.
     */
    private static void validate(AssetHubProperties.Ai ai) {
        if (ai.isVertexAi()) {
            if (isBlank(ai.projectId())) {
                throw new IllegalStateException(
                        "asset-hub.ai.project-id is required when asset-hub.ai-enabled=true and "
                                + "asset-hub.ai.transport=VERTEX_AI. Set ASSET_HUB_AI_PROJECT_ID (or GCP_PROJECT_ID), "
                                + "or switch to asset-hub.ai.transport=GEMINI_API to use an API key instead.");
            }
            if (isBlank(ai.region())) {
                throw new IllegalStateException(
                        "asset-hub.ai.region is required when asset-hub.ai.transport=VERTEX_AI. Set ASSET_HUB_AI_REGION.");
            }
        } else if (isBlank(ai.apiKey())) {
            throw new IllegalStateException(
                    "asset-hub.ai.api-key is required when asset-hub.ai-enabled=true and "
                            + "asset-hub.ai.transport=GEMINI_API. Set ASSET_HUB_AI_API_KEY, or switch to "
                            + "asset-hub.ai.transport=VERTEX_AI to authenticate with Application Default Credentials.");
        }
        if (isBlank(ai.model())) {
            throw new IllegalStateException("asset-hub.ai.model must not be blank. Set ASSET_HUB_AI_MODEL.");
        }
    }

    /**
     * Application Default Credentials, resolved lazily on first use rather than at startup — the
     * same choice PipelineRestClientConfig makes, so that booting the app does not require
     * {@code gcloud auth application-default login} until a suggestion is actually requested.
     */
    private static RestClient vertexAiClient(AssetHubProperties.Ai ai) {
        Supplier<GoogleCredentials> credentials = lazily(() -> {
            try {
                return GoogleCredentials.getApplicationDefault()
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "No Google credentials available for Asset Hub AI suggestions. Run "
                                + "'gcloud auth application-default login', switch to "
                                + "asset-hub.ai.transport=GEMINI_API, or set asset-hub.ai-enabled=false.", e);
            }
        });

        return RestClient.builder()
                .baseUrl("https://" + ai.region() + "-aiplatform.googleapis.com")
                .requestFactory(requestFactory(ai))
                .requestInterceptor((request, body, execution) -> {
                    GoogleCredentials resolved = credentials.get();
                    resolved.refreshIfExpired();
                    request.getHeaders().setBearerAuth(resolved.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                })
                .build();
    }

    /**
     * The key goes in {@code x-goog-api-key} rather than a {@code ?key=} query parameter: a header
     * does not end up in access logs, proxy logs, or exception messages that echo the request URI.
     */
    private static RestClient geminiApiClient(AssetHubProperties.Ai ai) {
        return RestClient.builder()
                .baseUrl("https://generativelanguage.googleapis.com")
                .requestFactory(requestFactory(ai))
                .defaultHeader("x-goog-api-key", ai.apiKey())
                .build();
    }

    private static SimpleClientHttpRequestFactory requestFactory(AssetHubProperties.Ai ai) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(ai.timeoutSeconds()).toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        return requestFactory;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static <T> Supplier<T> lazily(Supplier<T> delegate) {
        return new Supplier<>() {
            private volatile T value;

            @Override
            public T get() {
                T result = value;
                if (result == null) {
                    synchronized (this) {
                        result = value;
                        if (result == null) {
                            value = result = delegate.get();
                        }
                    }
                }
                return result;
            }
        };
    }
}

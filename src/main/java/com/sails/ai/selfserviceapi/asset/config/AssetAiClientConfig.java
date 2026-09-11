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
 * Vertex AI REST client for {@link com.sails.ai.selfserviceapi.asset.ai.GeminiAssetAiProvider}.
 * Mirrors deploypipeline.config.PipelineRestClientConfig's googleApiClient: Application Default
 * Credentials resolved lazily (so booting the app locally with asset-hub.ai-enabled=false needs no
 * `gcloud auth application-default login`), SimpleClientHttpRequestFactory instead of Spring's
 * default JDK HttpClient for the same loopback-socket-in-sandboxes reason documented there.
 */
@Configuration
@ConditionalOnProperty(prefix = "asset-hub", name = "ai-enabled", havingValue = "true")
public class AssetAiClientConfig {

    @Bean
    public RestClient vertexAiRestClient(AssetHubProperties properties) {
        Supplier<GoogleCredentials> credentials = lazily(() -> {
            try {
                return GoogleCredentials.getApplicationDefault()
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "No Google credentials available for Asset Hub AI suggestions. Run "
                                + "'gcloud auth application-default login', or set asset-hub.ai-enabled=false.", e);
            }
        });

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        int timeoutMillis = (int) Duration.ofSeconds(properties.ai().timeoutSeconds()).toMillis();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);

        return RestClient.builder()
                .baseUrl("https://" + properties.ai().region() + "-aiplatform.googleapis.com")
                .requestFactory(requestFactory)
                .requestInterceptor((request, body, execution) -> {
                    GoogleCredentials resolved = credentials.get();
                    resolved.refreshIfExpired();
                    request.getHeaders().setBearerAuth(resolved.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                })
                .build();
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

package com.sails.ai.selfserviceapi.onboarding.generate.model;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * {@code RestClient}s for the two {@link ManifestDraftModel} adapters. Deliberately separate from
 * {@code PipelineRestClientConfig} — that class is scoped to the deploy pipeline (GitHub, Cloud
 * Build, Cloud Run); this feature is onboarding-only and lives beside it.
 *
 * <p>Same conventions as {@code PipelineRestClientConfig}: {@link SimpleClientHttpRequestFactory}
 * rather than Spring's default JDK {@code HttpClient} (its async selector opens a loopback socket
 * that fails in some sandboxed/corporate-network environments), with an explicit connect/read
 * timeout on every client — see {@link #timeoutFactory()}.
 */
@Configuration
public class DraftModelRestClientConfig {

    public static final String OLLAMA = "ollamaRestClient";
    public static final String VERTEX = "vertexAiRestClient";

    /** No token, no Authorization header — Ollama is a local, unauthenticated daemon. */
    @Bean(OLLAMA)
    public RestClient ollamaRestClient(DraftModelProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.ollama().baseUrl())
                .requestFactory(timeoutFactory())
                .build();
    }

    /**
     * Same GCP-auth pattern as {@code PipelineRestClientConfig.googleApiClient} — ADC, resolved and
     * refreshed lazily so a developer who never touches this feature doesn't need
     * {@code gcloud auth application-default login} just to boot the app. No new secret to manage:
     * this platform already authenticates to GCP the same way for Cloud Build and Cloud Run.
     */
    @Bean(VERTEX)
    public RestClient vertexAiRestClient() {
        Supplier<GoogleCredentials> credentials = lazily(() -> {
            try {
                return GoogleCredentials.getApplicationDefault()
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "No Google credentials available for Vertex AI. Run 'gcloud auth application-default"
                                + " login', or set poc-generator.provider to 'ollama'.", e);
            }
        });

        return RestClient.builder()
                .baseUrl("https://aiplatform.googleapis.com")
                .requestFactory(timeoutFactory())
                .requestInterceptor((request, body, execution) -> {
                    GoogleCredentials resolved = credentials.get();
                    resolved.refreshIfExpired();
                    request.getHeaders().setBearerAuth(resolved.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                })
                .build();
    }

    private static SimpleClientHttpRequestFactory timeoutFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        factory.setReadTimeout(Duration.ofSeconds(15));
        return factory;
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

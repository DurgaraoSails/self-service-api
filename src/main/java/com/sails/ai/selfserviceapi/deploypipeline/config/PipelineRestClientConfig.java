package com.sails.ai.selfserviceapi.deploypipeline.config;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.function.Supplier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * RestClients for GitHub and the Google Cloud APIs the pipeline calls directly (Cloud Build,
 * Cloud Run Admin — never Cloud Run itself, which is only ever reached through gcloud/Cloud
 * Build steps).
 *
 * Every client here uses {@link SimpleClientHttpRequestFactory} instead of Spring's default JDK
 * HttpClient — the latter opens a loopback socket for its async selector, which fails outright
 * in some sandboxed and corporate-network environments; none of these calls are frequent or
 * streaming enough for HTTP/2 to matter.
 *
 * Google credentials are resolved lazily, on first use, not at startup — a developer running the
 * {@code local} executor for the queue/version logic should not need
 * {@code gcloud auth application-default login} just to boot the app.
 */
@Configuration
public class PipelineRestClientConfig {

    /**
     * pipeline.github-token is the single source for this token — used here for the Authorization
     * header, and separately by GitHubService/LocalPipelineExecutor/BuildService for the same
     * value. One property rather than two that could silently drift out of sync. No boot-time
     * failure on it being blank — someone working on an unrelated part of the app shouldn't need
     * a GitHub token just to run `mvn spring-boot:run`; GitHubService fails loudly, with a clear
     * message, the moment it's actually asked to do something.
     */
    @Bean
    public RestClient gitHubRestClient(PipelineProperties properties) {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(new SimpleClientHttpRequestFactory())
                .defaultHeader("Authorization", "Bearer " + properties.githubToken())
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    @Bean
    public RestClient cloudBuildRestClient() {
        return googleApiClient("https://cloudbuild.googleapis.com/v1");
    }

    @Bean
    public RestClient cloudRunRestClient() {
        return googleApiClient("https://run.googleapis.com");
    }

    private RestClient googleApiClient(String baseUrl) {
        Supplier<GoogleCredentials> credentials = lazily(() -> {
            try {
                return GoogleCredentials.getApplicationDefault()
                        .createScoped("https://www.googleapis.com/auth/cloud-platform");
            } catch (IOException e) {
                throw new UncheckedIOException(
                        "No Google credentials available. Run 'gcloud auth application-default login', "
                                + "or set pipeline.executor to something that doesn't need GCP.", e);
            }
        });

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new SimpleClientHttpRequestFactory())
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

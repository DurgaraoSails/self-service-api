package com.sails.ai.selfserviceapi.config;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Value("${brevo.api-key}")
    private String brevoApiKey;

    @Value("${github.token}")
    private String gitHubToken;

    @Bean
    public RestClient brevoRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.brevo.com/v3")
                .defaultHeader("api-key", brevoApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public RestClient gitHubRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader("Authorization", "Bearer " + gitHubToken)
                .defaultHeader("Accept", "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /**
     * Authenticates via Application Default Credentials rather than a static key — locally this
     * is your own `gcloud auth application-default login` (or an impersonated self-service-api,
     * per the terraform README); in Cloud Run it's the service's attached identity automatically.
     */
    @Bean
    public RestClient cloudBuildRestClient() throws IOException {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");

        return RestClient.builder()
                .baseUrl("https://cloudbuild.googleapis.com/v1")
                // JDK HttpClient (Spring's default here) needs a local loopback socket for its
                // async selector — blocked in some sandboxed/corporate-network setups. This
                // avoids that class of failure entirely; we don't need HTTP/2 for occasional
                // Cloud Build API calls anyway.
                .requestFactory(new SimpleClientHttpRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    credentials.refreshIfExpired();
                    request.getHeaders().setBearerAuth(credentials.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                })
                .build();
    }
}

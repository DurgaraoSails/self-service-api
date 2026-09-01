package com.sails.ai.selfserviceapi.config;

import com.google.auth.oauth2.GoogleCredentials;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

// Every RestClient here uses SimpleClientHttpRequestFactory instead of Spring's default JDK
// HttpClient — the latter needs a local loopback socket for its async selector, which throws
// "Unable to establish loopback connection" in some sandboxed/restricted-network environments
// (confirmed here) and some corporate VPN/proxy setups on real machines. None of these calls
// are frequent/streaming enough to need HTTP/2, so there's no downside to the safer default.
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
                .requestFactory(new SimpleClientHttpRequestFactory())
                .defaultHeader("api-key", brevoApiKey)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    @Bean
    public RestClient gitHubRestClient() {
        return RestClient.builder()
                .baseUrl("https://api.github.com")
                .requestFactory(new SimpleClientHttpRequestFactory())
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
                .requestFactory(new SimpleClientHttpRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    credentials.refreshIfExpired();
                    request.getHeaders().setBearerAuth(credentials.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                })
                .build();
    }
}

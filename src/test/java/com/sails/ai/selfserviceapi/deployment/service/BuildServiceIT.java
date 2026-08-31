package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.google.auth.oauth2.GoogleCredentials;
import com.sails.ai.selfserviceapi.deployment.config.GcpProperties;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildRequest;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Submits a real Cloud Build job against DurgaraoSails/dummy-poc using whatever Application
 * Default Credentials are active locally. Only runs when RUN_CLOUD_BUILD_IT=true — this hits
 * real GCP billing/quota, unlike the other gated ITs, so it's opt-in even beyond having a token.
 *
 * Run directly: RUN_CLOUD_BUILD_IT=true mvnw test -Dtest=BuildServiceIT
 */
@EnabledIfEnvironmentVariable(named = "RUN_CLOUD_BUILD_IT", matches = "true")
class BuildServiceIT {

    @Test
    void submitsARealBuildAgainstDummyPoc() throws IOException {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault()
                .createScoped("https://www.googleapis.com/auth/cloud-platform");

        RestClient restClient = RestClient.builder()
                .baseUrl("https://cloudbuild.googleapis.com/v1")
                .requestFactory(new SimpleClientHttpRequestFactory())
                .requestInterceptor((request, body, execution) -> {
                    credentials.refreshIfExpired();
                    request.getHeaders().setBearerAuth(credentials.getAccessToken().getTokenValue());
                    return execution.execute(request, body);
                })
                .build();

        GcpProperties gcpProperties = new GcpProperties("sails-agenthub", "us-central1", "dev");
        BuildService buildService = new BuildService(restClient, gcpProperties);
        GitHubRepoRef repo = new GitHubRepoRef("DurgaraoSails", "dummy-poc");

        String buildId = buildService.submitBuild(new BuildRequest("dummy-poc", "1.0.0", repo));

        assertThat(buildId).isNotBlank();
        System.out.println("Submitted Cloud Build job: " + buildId);
    }
}

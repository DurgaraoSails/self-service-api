package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sails.ai.selfserviceapi.deployment.config.GcpProperties;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

class BuildServiceTest {

    private static final GcpProperties GCP_PROPERTIES =
            new GcpProperties("sails-agenthub", "us-central1", "dev");

    private static final String OPERATION_RESPONSE = """
            {
              "name": "operations/build/sails-agenthub/abc-123",
              "metadata": {
                "build": { "id": "abc-123", "status": "QUEUED" }
              }
            }
            """;

    @Test
    void submitsABuildThatNeverPutsTheGitHubTokenInPlainArgs() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://cloudbuild.googleapis.com/v1");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();

        StringBuilder capturedBody = new StringBuilder();

        server.expect(requestTo("https://cloudbuild.googleapis.com/v1/projects/sails-agenthub/builds"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(captureBody(capturedBody))
                .andRespond(withSuccess(OPERATION_RESPONSE, MediaType.APPLICATION_JSON));

        BuildService buildService = new BuildService(restClient, GCP_PROPERTIES);
        GitHubRepoRef repo = new GitHubRepoRef("DurgaraoSails", "dummy-poc");

        String buildId = buildService.submitBuild(new BuildRequest("dummy-poc", "1.0.0", repo));

        assertThat(buildId).isEqualTo("abc-123");

        String body = capturedBody.toString();
        assertThat(body)
                .as("clone step references the token by name, never inline")
                .contains("x-access-token:$$GITHUB_TOKEN@github.com/DurgaraoSails/dummy-poc.git")
                .doesNotContain("ghp_")
                .doesNotContain("github_pat_");
        assertThat(body).contains("\"env\":\"GITHUB_TOKEN\"");
        assertThat(body).contains("projects/sails-agenthub/secrets/github-token-dev/versions/latest");
        assertThat(body).contains("projects/sails-agenthub/serviceAccounts/self-service-builder-dev@sails-agenthub.iam.gserviceaccount.com");
        assertThat(body).contains("us-central1-docker.pkg.dev/sails-agenthub/poc-images/dummy-poc/app:1.0.0");
        assertThat(body).contains("\"logging\":\"CLOUD_LOGGING_ONLY\"");
        assertThat(body).contains("--service-account=poc-runtime-dev@sails-agenthub.iam.gserviceaccount.com");
        assertThat(body).contains("--member=serviceAccount:self-service-gateway-dev@sails-agenthub.iam.gserviceaccount.com");

        server.verify();
    }

    private static RequestMatcher captureBody(StringBuilder sink) {
        return (ClientHttpRequest request) -> {
            String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                    .getBodyAsString(StandardCharsets.UTF_8);
            sink.append(body);
        };
    }
}

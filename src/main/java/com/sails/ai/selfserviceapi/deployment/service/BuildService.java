package com.sails.ai.selfserviceapi.deployment.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sails.ai.selfserviceapi.deployment.config.GcpProperties;
import com.sails.ai.selfserviceapi.deployment.exception.CloudBuildApiException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Service
public class BuildService {

    private final RestClient cloudBuildRestClient;
    private final GcpProperties gcpProperties;

    public BuildService(RestClient cloudBuildRestClient, GcpProperties gcpProperties) {
        this.cloudBuildRestClient = cloudBuildRestClient;
        this.gcpProperties = gcpProperties;
    }

    /** Submits the tag-build-push-deploy job for one POC release. */
    public BuildSubmission submitBuild(BuildRequest request) {
        String image = imageUri(request.slug(), request.releaseTag());
        BuildConfig config = buildConfig(request, image);
        try {
            SubmitBuildResponse response = cloudBuildRestClient.post()
                    .uri("/projects/{project}/builds", gcpProperties.projectId())
                    .body(config)
                    .retrieve()
                    .body(SubmitBuildResponse.class);
            return new BuildSubmission(response.metadata().build().id(), image);
        } catch (RestClientResponseException e) {
            throw new CloudBuildApiException(
                    "Failed to submit build for %s@%s: %d %s".formatted(
                            request.repo(), request.releaseTag(), e.getStatusCode().value(), e.getResponseBodyAsString()),
                    e);
        }
    }

    private String imageUri(String slug, String releaseTag) {
        return "%s-docker.pkg.dev/%s/poc-images/%s/app:%s".formatted(
                gcpProperties.region(), gcpProperties.projectId(), slug, releaseTag);
    }

    private BuildConfig buildConfig(BuildRequest request, String image) {

        // The token is never baked into args/substitutions — those are stored permanently on the
        // Build resource and visible to anyone with build-read access. It only ever exists as a
        // runtime env var (secretEnv, pulled fresh from Secret Manager by the builder identity),
        // referenced here by name ($$GITHUB_TOKEN), never by value.
        String cloneCommand = "git clone --branch %s --depth 1 https://x-access-token:$$GITHUB_TOKEN@github.com/%s/%s.git src"
                .formatted(request.releaseTag(), request.repo().owner(), request.repo().name());

        List<BuildStep> steps = List.of(
                new BuildStep("gcr.io/cloud-builders/git", "bash",
                        List.of("-c", cloneCommand), List.of("GITHUB_TOKEN")),
                new BuildStep("gcr.io/cloud-builders/docker", null,
                        List.of("build", "-t", image, "src"), null),
                new BuildStep("gcr.io/cloud-builders/docker", null,
                        List.of("push", image), null),
                new BuildStep("gcr.io/google.com/cloudsdktool/cloud-sdk", "gcloud",
                        List.of("run", "deploy", request.slug(),
                                "--image=" + image,
                                "--region=" + gcpProperties.region(),
                                "--service-account=" + gcpProperties.serviceAccountEmail("poc-runtime"),
                                "--no-allow-unauthenticated"),
                        null),
                // Without this, the gateway can never reach the POC it just deployed —
                // --no-allow-unauthenticated locks it to nobody until explicitly granted.
                new BuildStep("gcr.io/google.com/cloudsdktool/cloud-sdk", "gcloud",
                        List.of("run", "services", "add-iam-policy-binding", request.slug(),
                                "--region=" + gcpProperties.region(),
                                "--member=serviceAccount:" + gcpProperties.serviceAccountEmail("self-service-gateway"),
                                "--role=roles/run.invoker"),
                        null));

        AvailableSecrets availableSecrets = new AvailableSecrets(
                List.of(new SecretManagerSecret(gcpProperties.secretVersionName("github-token"), "GITHUB_TOKEN")));

        return new BuildConfig(
                steps,
                availableSecrets,
                gcpProperties.serviceAccountResourceName("self-service-builder"),
                new BuildOptions("CLOUD_LOGGING_ONLY"));
    }

    public record BuildRequest(String slug, String releaseTag, GitHubRepoRef repo) {
    }

    public record BuildSubmission(String cloudBuildId, String imageUri) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BuildStep(String name, String entrypoint, List<String> args, List<String> secretEnv) {
    }

    private record SecretManagerSecret(String versionName, String env) {
    }

    private record AvailableSecrets(List<SecretManagerSecret> secretManager) {
    }

    private record BuildOptions(String logging) {
    }

    private record BuildConfig(
            List<BuildStep> steps,
            AvailableSecrets availableSecrets,
            String serviceAccount,
            BuildOptions options) {
    }

    private record SubmitBuildResponse(OperationMetadata metadata) {
    }

    private record OperationMetadata(BuildInfo build) {
    }

    private record BuildInfo(String id, String status) {
    }
}

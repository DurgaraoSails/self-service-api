package com.sails.ai.selfserviceapi.deploypipeline.build;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Submits Cloud Build jobs and blocks until they finish. Build configs are constructed per
 * deployment and sent inline rather than stored as triggers — nothing fires on a git push, every
 * build is one the async pipeline run asked for.
 *
 * Two separate submissions rather than one five-step build: a build-and-push job, then a deploy
 * job. That maps directly onto the BUILDING → DEPLOYING status transitions the caller reports,
 * and it means a rollback (which only ever needs the deploy half) reuses the same method.
 */
@Service
public class BuildService {

    private final RestClient cloudBuildRestClient;
    private final GcpProperties gcp;
    private final PipelineProperties properties;

    public BuildService(RestClient cloudBuildRestClient, GcpProperties gcp, PipelineProperties properties) {
        this.cloudBuildRestClient = cloudBuildRestClient;
        this.gcp = gcp;
        this.properties = properties;
    }

    /** Clones the tag, builds, and pushes. Blocks until Cloud Build finishes; returns the pushed image URI. */
    public String buildAndPush(GitHubRepoRef repo, String versionLabel, String slug) {
        String image = gcp.imageUri(slug, versionLabel);

        List<BuildStep> steps = new ArrayList<>();
        steps.add(cloneStep(versionLabel, repo));
        steps.add(new BuildStep("gcr.io/cloud-builders/docker", null, List.of("build", "-t", image, "src"), null));
        steps.add(new BuildStep("gcr.io/cloud-builders/docker", null, List.of("push", image), null));

        String buildId = submit(steps, availableSecrets());
        awaitSuccess(buildId, "build " + image);
        return image;
    }

    /** Deploys an image (freshly built or pre-existing) and, if configured, grants the gateway access. */
    public void deploy(String slug, String image) {
        List<BuildStep> steps = new ArrayList<>();
        steps.add(deployStep(slug, image));
        if (properties.grantGatewayInvoker()) {
            steps.add(grantGatewayInvokerStep(slug));
        }

        String buildId = submit(steps, null);
        awaitSuccess(buildId, "deploy " + slug);
    }

    private void awaitSuccess(String buildId, String description) {
        Instant deadline = Instant.now().plus(properties.buildTimeout());

        while (true) {
            BuildStatus status = getStatus(buildId);
            if (status.isSuccess()) {
                return;
            }
            if (status.isTerminal()) {
                throw new CloudBuildApiException("Failed to " + description + ": "
                        + (status.failureDetail() != null ? status.failureDetail() : "Cloud Build reported " + status.status()));
            }
            if (Instant.now().isAfter(deadline)) {
                throw new CloudBuildApiException(
                        "Timed out after %s waiting to %s (build %s)".formatted(properties.buildTimeout(), description, buildId));
            }
            sleep(properties.buildPollInterval());
        }
    }

    private String submit(List<BuildStep> steps, AvailableSecrets secrets) {
        BuildConfig config = new BuildConfig(steps, secrets, buildServiceAccount(), logging());
        try {
            SubmitBuildResponse response = cloudBuildRestClient.post()
                    .uri("/projects/{project}/builds", gcp.projectId())
                    .body(config)
                    .retrieve()
                    .body(SubmitBuildResponse.class);
            return response.metadata().build().id();
        } catch (RestClientResponseException e) {
            throw new CloudBuildApiException("Failed to submit build: %d %s"
                    .formatted(e.getStatusCode().value(), e.getResponseBodyAsString()), e);
        }
    }

    private BuildStatus getStatus(String cloudBuildId) {
        try {
            BuildStatusResponse response = cloudBuildRestClient.get()
                    .uri("/projects/{project}/builds/{id}", gcp.projectId(), cloudBuildId)
                    .retrieve()
                    .body(BuildStatusResponse.class);
            return new BuildStatus(response.status(),
                    response.failureInfo() == null ? null : response.failureInfo().detail());
        } catch (RestClientResponseException e) {
            throw new CloudBuildApiException("Failed to fetch status for build %s: %d %s"
                    .formatted(cloudBuildId, e.getStatusCode().value(), e.getResponseBodyAsString()), e);
        }
    }

    /**
     * How the clone authenticates, in descending order of safety.
     *
     * <p>With a Secret Manager secret the token is referenced by name and resolved inside the
     * build, so it never lands on the Build resource. With an inline token it is interpolated
     * into the step's arguments, which Cloud Build stores permanently — a real exposure, only
     * meant for a local dev token on a repo you don't mind exposing. With neither, the clone is
     * anonymous, which is all a public repository needs.
     */
    private BuildStep cloneStep(String versionLabel, GitHubRepoRef repo) {
        String repoPath = "github.com/%s/%s.git".formatted(repo.owner(), repo.name());

        if (properties.usesSecretManagerToken()) {
            String command = "git clone --branch %s --depth 1 https://x-access-token:$$GITHUB_TOKEN@%s src"
                    .formatted(versionLabel, repoPath);
            return new BuildStep("gcr.io/cloud-builders/git", "bash", List.of("-c", command), List.of("GITHUB_TOKEN"));
        }

        if (properties.hasGithubToken()) {
            String command = "git clone --branch %s --depth 1 https://x-access-token:%s@%s src"
                    .formatted(versionLabel, properties.githubToken(), repoPath);
            return new BuildStep("gcr.io/cloud-builders/git", "bash", List.of("-c", command), null);
        }

        return new BuildStep("gcr.io/cloud-builders/git", null,
                List.of("clone", "--branch", versionLabel, "--depth", "1", "https://" + repoPath, "src"), null);
    }

    private AvailableSecrets availableSecrets() {
        if (!properties.usesSecretManagerToken()) {
            return null;
        }
        return new AvailableSecrets(List.of(new SecretManagerSecret(
                gcp.secretVersionName(properties.githubTokenSecretId()), "GITHUB_TOKEN")));
    }

    private BuildStep deployStep(String slug, String image) {
        return new BuildStep("gcr.io/google.com/cloudsdktool/cloud-sdk", "gcloud",
                List.of("run", "deploy", slug,
                        "--image=" + image,
                        "--region=" + gcp.region(),
                        "--service-account=" + gcp.serviceAccountEmail("poc-runtime"),
                        properties.allowUnauthenticated() ? "--allow-unauthenticated" : "--no-allow-unauthenticated"),
                null);
    }

    /**
     * Without this the gateway can never reach what was just deployed —
     * --no-allow-unauthenticated locks the service to nobody until something is granted.
     */
    private BuildStep grantGatewayInvokerStep(String slug) {
        return new BuildStep("gcr.io/google.com/cloudsdktool/cloud-sdk", "gcloud",
                List.of("run", "services", "add-iam-policy-binding", slug,
                        "--region=" + gcp.region(),
                        "--member=serviceAccount:" + gcp.serviceAccountEmail("self-service-gateway"),
                        "--role=roles/run.invoker"),
                null);
    }

    /**
     * Blank means "run as Cloud Build's own default service account". That is what a local
     * account without an iam.serviceAccountUser binding on self-service-builder must use.
     */
    private String buildServiceAccount() {
        return properties.usesCustomBuildServiceAccount()
                ? gcp.serviceAccountResourceName(properties.buildServiceAccount())
                : null;
    }

    /** Required: Cloud Build rejects any build with a custom service account and default logging. */
    private BuildOptions logging() {
        return new BuildOptions("CLOUD_LOGGING_ONLY");
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudBuildApiException("Interrupted while waiting on a build", e);
        }
    }

    private record BuildStatus(String status, String failureDetail) {
        private static final Set<String> TERMINAL =
                Set.of("SUCCESS", "FAILURE", "INTERNAL_ERROR", "TIMEOUT", "CANCELLED", "EXPIRED");

        boolean isTerminal() {
            return TERMINAL.contains(status);
        }

        boolean isSuccess() {
            return "SUCCESS".equals(status);
        }
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BuildConfig(List<BuildStep> steps, AvailableSecrets availableSecrets,
                                String serviceAccount, BuildOptions options) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SubmitBuildResponse(OperationMetadata metadata) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record OperationMetadata(BuildInfo build) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BuildInfo(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BuildStatusResponse(String status, FailureInfo failureInfo) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record FailureInfo(String type, String detail) {
    }
}

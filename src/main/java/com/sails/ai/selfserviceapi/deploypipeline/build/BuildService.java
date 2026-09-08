package com.sails.ai.selfserviceapi.deploypipeline.build;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunDeployCommandBuilder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Submits Cloud Build jobs and blocks until they finish. Build configs are constructed per
 * deployment and sent inline rather than stored as triggers — nothing fires on a git push, every
 * build is one the async pipeline run asked for.
 *
 * Two separate submissions rather than one job: a build-and-push job (one clone, then a
 * build/push step pair per manifest container), then a deploy job. That maps directly onto the
 * BUILDING → DEPLOYING status transitions the caller reports regardless of container count, and it
 * means a rollback (which only ever needs the deploy half) reuses the same method.
 */
@Service
public class BuildService {

    /**
     * Secret Manager secret holding the GitHub token the clone step authenticates with. A constant
     * rather than a property: the platform owns the name, {@code GcpProperties.secretVersionName}
     * appends the environment, and self-service-terraform creates exactly this secret. One less
     * thing an environment can get subtly wrong.
     */
    private static final String GITHUB_TOKEN_SECRET_ID = "github-token";

    private final RestClient cloudBuildRestClient;
    private final GcpProperties gcp;
    private final PipelineProperties properties;
    private final CloudRunDeployCommandBuilder deployCommandBuilder;

    public BuildService(RestClient cloudBuildRestClient, GcpProperties gcp, PipelineProperties properties,
                         CloudRunDeployCommandBuilder deployCommandBuilder) {
        this.cloudBuildRestClient = cloudBuildRestClient;
        this.gcp = gcp;
        this.properties = properties;
        this.deployCommandBuilder = deployCommandBuilder;
    }

    /**
     * Clones the tag once, then builds and pushes one image per manifest container — all as a
     * single Cloud Build job, so BUILDING stays one status transition regardless of container
     * count. Blocks until Cloud Build finishes; returns each pushed image's URI, keyed by
     * container name.
     */
    public Map<String, String> buildAndPush(GitHubRepoRef repo, String versionLabel, String slug, PocManifest manifest) {
        List<BuildStep> steps = new ArrayList<>();
        steps.add(cloneStep(versionLabel, repo));

        Map<String, String> images = new LinkedHashMap<>();
        for (ManifestContainer container : manifest.containers()) {
            String image = gcp.imageUri(slug, versionLabel, container.name());
            images.put(container.name(), image);
            steps.add(buildStep(container, image));
            steps.add(new BuildStep("gcr.io/cloud-builders/docker", null, List.of("push", image), null));
        }

        String buildId = submit(steps, availableSecrets());
        awaitSuccess(buildId, "build " + slug);
        return images;
    }

    /**
     * Each container may declare its own Dockerfile/build context, both independently relative to
     * the repo root cloned into "src" — see {@link ManifestContainer}'s javadoc for why this isn't
     * dockerfile-relative-to-context.
     */
    private BuildStep buildStep(ManifestContainer container, String image) {
        return new BuildStep("gcr.io/cloud-builders/docker", null,
                List.of("build", "-f", "src/" + container.dockerfile(), "-t", image, "src/" + container.context()), null);
    }

    /**
     * Deploys every manifest container as one Cloud Run service (freshly built or pre-existing
     * images) and, if configured, grants self-service-api access.
     */
    public void deploy(String slug, PocManifest manifest, Map<String, String> imagesByContainer) {
        List<BuildStep> steps = new ArrayList<>();
        steps.add(deployStep(slug, manifest, imagesByContainer));
        if (properties.grantApiInvoker()) {
            steps.add(grantApiInvokerStep(slug));
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
     * The clone always authenticates from Secret Manager, and the token never becomes part of
     * anything that outlives the step.
     *
     * <p>There is deliberately no fallback. An inline token would be interpolated into the step's
     * arguments, which Cloud Build stores permanently on the Build resource for anyone with build
     * read access; an anonymous clone would work only for a public repo and fail confusingly for
     * every other one. A missing or unreadable secret now fails the build outright, which is the
     * honest outcome — the fix is an IAM grant, not a quieter code path.
     *
     * <p>Three details carry the token safely, and each is load-bearing:
     *
     * <ul>
     *   <li>{@code $$} is Cloud Build's escape for a literal {@code $}. What is stored on the Build
     *       resource is {@code $GITHUB_TOKEN} as text; the value is substituted by the shell at
     *       execution time from {@code secretEnv}. Writing a single {@code $} would make Cloud
     *       Build try to resolve its own substitution and fail.</li>
     *   <li>The credential goes in an {@code Authorization} header, not in the clone URL. A
     *       credentialed URL is written verbatim into {@code src/.git/config} as
     *       {@code remote.origin.url}, and {@code /workspace} is shared with every later step — so
     *       a token in the URL would outlive this step, and git could echo it into the build log
     *       on a clone failure.</li>
     *   <li>{@code .git} is deleted immediately. A manifest may set {@code context: "."} (the
     *       default for a repo with no poc.yaml), which makes the whole checkout the docker build
     *       context — a {@code COPY . .} with no .dockerignore would otherwise bake git metadata
     *       into a published image layer. Nothing downstream needs history: later steps only run
     *       docker build, and poc.yaml is read through the GitHub API, not from this checkout.</li>
     * </ul>
     */
    BuildStep cloneStep(String versionLabel, GitHubRepoRef repo) {
        String repoUrl = "https://github.com/%s/%s.git".formatted(repo.owner(), repo.name());
        String command = """
                set -e
                AUTH=$$(printf 'x-access-token:%%s' "$$GITHUB_TOKEN" | base64 -w0)
                git -c http.extraHeader="Authorization: Basic $$AUTH" \
                    clone --branch %s --depth 1 %s src
                rm -rf src/.git
                """.formatted(versionLabel, repoUrl);

        return new BuildStep("gcr.io/cloud-builders/git", "bash", List.of("-c", command), List.of("GITHUB_TOKEN"));
    }

    /**
     * The secret's name is a platform convention rather than configuration:
     * {@code GcpProperties.secretVersionName} appends the environment, so this resolves to
     * {@code projects/<project>/secrets/github-token-<env>/versions/latest} and dev/prod separate
     * on their own. It matches {@code google_secret_manager_secret.github_token} in
     * self-service-terraform, which also grants the build service account read access to it.
     *
     * <p>Unconditional: a build with no way to authenticate its clone should fail loudly rather
     * than fall back to something weaker. Note this grants the build access to the secret's *name*
     * only — Cloud Build resolves the value itself, so no token passes through this app.
     */
    private AvailableSecrets availableSecrets() {
        return new AvailableSecrets(List.of(new SecretManagerSecret(
                gcp.secretVersionName(GITHUB_TOKEN_SECRET_ID), "GITHUB_TOKEN")));
    }
    
    /**
     * gcloud requires every non-container-level flag (--region, --service-account,
     * --allow-unauthenticated) to precede the first --container= flag: once a --container= flag
     * appears, anything after it is parsed as scoped to that container, and gcloud rejects a
     * flag it doesn't recognize as container-level with a usage error (exit code 2). A
     * single-container manifest never emits --container= at all, so this ordering is harmless
     * there too.
     */
    BuildStep deployStep(String slug, PocManifest manifest, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>(List.of("run", "deploy", slug));
        args.add("--region=" + gcp.region());
        args.add("--service-account=" + gcp.serviceAccountEmail("poc-runtime"));
        args.add(properties.allowUnauthenticated() ? "--allow-unauthenticated" : "--no-allow-unauthenticated");
        args.addAll(deployCommandBuilder.buildServiceArgs(manifest));
        args.addAll(deployCommandBuilder.buildContainerArgs(slug, manifest, imagesByContainer));
        return new BuildStep("gcr.io/google.com/cloudsdktool/cloud-sdk", "gcloud", args, null);
    }

    /**
     * Only relevant when this POC opted out of the default public model
     * (pipeline.allow-unauthenticated=false) — without this, self-service-api itself couldn't
     * reach what was just deployed either, since --no-allow-unauthenticated locks the service to
     * nobody until something is granted. End users never go through self-service-api to reach a
     * POC either way: the portal iframes a POC's Cloud Run URL directly from the browser, with no
     * proxy in between.
     */
    private BuildStep grantApiInvokerStep(String slug) {
        return new BuildStep("gcr.io/google.com/cloudsdktool/cloud-sdk", "gcloud",
                List.of("run", "services", "add-iam-policy-binding", slug,
                        "--region=" + gcp.region(),
                        "--member=serviceAccount:" + gcp.serviceAccountEmail("self-service-api"),
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
    record BuildStep(String name, String entrypoint, List<String> args, List<String> secretEnv) {
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

package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.build.LocalBuildException;
import com.sails.ai.selfserviceapi.deploypipeline.build.ProcessRunner;
import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunDeployCommandBuilder;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunService;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the pipeline on this machine: clone, docker build, push to Artifact Registry, deploy to
 * Cloud Run — every GCP call made with the developer's own Application Default Credentials. Needs
 * no service-account impersonation, no Secret Manager access, and no Cloud Build permissions, so
 * it works before the platform's IAM bindings exist.
 *
 * <p>Not for production. A Cloud Run Job has no Docker daemon, so this only ever runs where
 * {@code self-service-api} itself runs directly on a developer's machine.
 */
@Component
@ConditionalOnProperty(prefix = "pipeline", name = "executor", havingValue = "local", matchIfMissing = true)
public class LocalPipelineExecutor implements PipelineExecutor {

    private static final Logger log = LoggerFactory.getLogger(LocalPipelineExecutor.class);

    private final ProcessRunner processRunner;
    private final GcpProperties gcp;
    private final PipelineProperties properties;
    private final CloudRunService cloudRunService;
    private final CloudRunDeployCommandBuilder deployCommandBuilder;

    public LocalPipelineExecutor(ProcessRunner processRunner, GcpProperties gcp, PipelineProperties properties,
                                  CloudRunService cloudRunService, CloudRunDeployCommandBuilder deployCommandBuilder) {
        this.processRunner = processRunner;
        this.gcp = gcp;
        this.properties = properties;
        this.cloudRunService = cloudRunService;
        this.deployCommandBuilder = deployCommandBuilder;
    }

    @Override
    public Map<String, String> buildAndPushImages(GitHubRepoRef repo, String versionLabel, String pocSlug, PocManifest manifest) {
        Path workspace = createWorkspace(pocSlug, versionLabel);
        Map<String, String> imagesByContainer = new LinkedHashMap<>();

        try {
            // Cloned once — every container's dockerfile/context is a path within this same
            // checkout, so N containers from one repo never need N clones.
            clone(repo, versionLabel, workspace);
            File repoRoot = workspace.resolve("src").toFile();

            for (ManifestContainer container : manifest.containers()) {
                String image = gcp.imageUri(pocSlug, container.name(), versionLabel);
                run(repoRoot, "docker", "build", "-t", image,
                        "-f", childPath(repoRoot, container.dockerfile()),
                        childPath(repoRoot, container.context()));
                run(repoRoot, "docker", "push", image);
                imagesByContainer.put(container.name(), image);
            }

            return imagesByContainer;
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Override
    public String deploy(String pocSlug, String versionLabel, PocManifest manifest, Map<String, String> imagesByContainer) {
        // CloudRunDeployCommandBuilder's output is the args *after* "gcloud" — that's what a Cloud
        // Build step wants (entrypoint="gcloud", args=this list), but running it as a real OS
        // process needs "gcloud" as argv[0] itself, so ProcessRunner's gcloud->gcloud.cmd
        // Windows handling actually triggers.
        List<String> deployArgs = new ArrayList<>();
        deployArgs.add("gcloud");
        deployArgs.addAll(deployCommandBuilder.buildDeployArgs(pocSlug, versionLabel, manifest, imagesByContainer, gcp, properties));
        // --project= only here, not in the shared builder: local's gcloud relies on whatever
        // project the developer's own config defaults to unless told otherwise, whereas a Cloud
        // Build step's gcloud is already scoped to the build's project via the Cloud Build API
        // call itself. Must land before the first --container=, per gcloud's own rule that
        // non-container-specific flags come first — inserted right after the service name to
        // guarantee that regardless of how many top-level flags the builder adds later.
        deployArgs.add(4, "--project=" + gcp.projectId());
        run(null, deployArgs.toArray(String[]::new));

        if (properties.grantApiInvoker()) {
            run(null, "gcloud", "run", "services", "add-iam-policy-binding", pocSlug,
                    "--region=" + gcp.region(),
                    "--project=" + gcp.projectId(),
                    "--member=serviceAccount:" + gcp.serviceAccountEmail("self-service-api"),
                    "--role=roles/run.invoker",
                    "--quiet");
        } else {
            log.warn("Skipping the self-service-api invoker grant (pipeline.grant-api-invoker=false) — "
                    + "'{}' is deployed but self-service-api cannot reach it yet", pocSlug);
        }

        return cloudRunService.getServiceUrl(pocSlug);
    }

    /** {@code dockerfile}/{@code context} are relative to the repo root — resolved, never trusted raw. */
    private String childPath(File repoRoot, String relative) {
        return repoRoot.toPath().resolve(relative).normalize().toString();
    }

    private void clone(GitHubRepoRef repo, String tag, Path workspace) {
        // A token here never leaves this machine — it goes into a subprocess argument and the
        // workspace is deleted afterwards. Omitted entirely for a public repository.
        String url = properties.hasGithubToken()
                ? "https://x-access-token:%s@github.com/%s/%s.git".formatted(
                        properties.githubToken(), repo.owner(), repo.name())
                : "https://github.com/%s/%s.git".formatted(repo.owner(), repo.name());

        run(workspace.toFile(), "git", "clone", "--branch", tag, "--depth", "1", url, "src");
    }

    private void run(File workingDir, String... command) {
        processRunner.run(workingDir, properties.commandTimeout(), command);
    }

    private Path createWorkspace(String slug, String versionLabel) {
        try {
            String prefix = "poc-%s-%s-".formatted(slug, versionLabel);
            return properties.workspaceDir() == null || properties.workspaceDir().isBlank()
                    ? Files.createTempDirectory(prefix)
                    : Files.createTempDirectory(Files.createDirectories(Path.of(properties.workspaceDir())), prefix);
        } catch (IOException e) {
            throw new LocalBuildException("Could not create a build workspace", e);
        }
    }

    /**
     * Best-effort: a workspace left behind wastes disk but must never mask the build's own
     * failure, which is the thing worth reporting.
     */
    private void deleteRecursively(Path root) {
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Windows holds git pack files briefly after clone; not worth failing over.
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up the workspace at {}", root, e);
        }
    }
}

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

    /**
     * Phase 1 note: this loops over every manifest container the same way {@code BuildService}
     * does, but is untested against a real multi-container repo — full local multi-container
     * support (matching Cloud Build's behavior exactly) is Phase 2. Kept correct rather than a
     * stub since it costs nothing extra: the loop and the shared {@link CloudRunDeployCommandBuilder}
     * already do the right thing for one container, which is all this executor needs to keep
     * working today.
     */
    @Override
    public Map<String, String> buildAndPushImages(GitHubRepoRef repo, String versionLabel, String pocSlug, PocManifest manifest) {
        Path workspace = createWorkspace(pocSlug, versionLabel);

        try {
            clone(repo, versionLabel, workspace);
            File source = workspace.resolve("src").toFile();
            Map<String, String> images = new LinkedHashMap<>();
            for (ManifestContainer container : manifest.containers()) {
                String image = gcp.imageUri(pocSlug, versionLabel, container.name());
                images.put(container.name(), image);
                run(source, "docker", "build", "-f", container.dockerfile(), "-t", image, container.context());
                run(source, "docker", "push", image);
            }
            return images;
        } finally {
            deleteRecursively(workspace);
        }
    }

    @Override
    public String deploy(String pocSlug, PocManifest manifest, Map<String, String> imagesByContainer) {
        List<String> args = new ArrayList<>(List.of("run", "deploy", pocSlug));
        args.addAll(deployCommandBuilder.buildContainerArgs(manifest, imagesByContainer));
        args.add("--region=" + gcp.region());
        args.add("--project=" + gcp.projectId());
        args.add("--service-account=" + gcp.serviceAccountEmail("poc-runtime"));
        args.add(properties.allowUnauthenticated() ? "--allow-unauthenticated" : "--no-allow-unauthenticated");
        args.add("--quiet");
        run(null, prepend("gcloud", args));

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

    private String[] prepend(String head, List<String> tail) {
        String[] command = new String[tail.size() + 1];
        command[0] = head;
        for (int i = 0; i < tail.size(); i++) {
            command[i + 1] = tail.get(i);
        }
        return command;
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

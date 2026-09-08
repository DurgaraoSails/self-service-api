package com.sails.ai.selfserviceapi.deploypipeline.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.GcpProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunDeployCommandBuilder;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the two build steps whose exact text is load-bearing and cannot be checked by reading:
 * the clone (a credential handling problem) and the deploy (a gcloud flag-grammar problem).
 *
 * <p>The deploy assertion replaces one that lived in the deleted LocalPipelineExecutorTest. That
 * executor is gone, but the constraint it pinned — every service-level flag ahead of the first
 * --container= — still governs this one, and nothing else asserted it.
 *
 * <p>No RestClient is needed: both methods build a step, and only submit() talks to Cloud Build.
 */
class BuildServiceTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("DurgaraoSails", "poc-integration-testbed");

    private final GcpProperties gcp = new GcpProperties("sails-agenthub", "us-central1", "dev");

    private final PipelineProperties pipeline = new PipelineProperties(
            "cloud-build", "self-service-builder", "ghp_realtokenvalue", false, true,
            Duration.ofMinutes(20), Duration.ofSeconds(10), null);

    private final BuildService buildService = new BuildService(null, gcp, pipeline,
            new CloudRunDeployCommandBuilder(
                    new PocRuntimeProperties(8080, "https://api.example.com", "https://portal.example.com")));

    // --- clone step: the token must not survive the step in any form ------------------------

    /**
     * $$ is Cloud Build's escape for a literal $. What gets stored on the Build resource is the
     * text $GITHUB_TOKEN; the value is substituted by the shell at run time from secretEnv. A
     * single $ would make Cloud Build attempt its own substitution instead.
     */
    @Test
    void referencesTheTokenAsAnEscapedPlaceholderResolvedFromSecretEnv() {
        BuildService.BuildStep step = buildService.cloneStep("1.0.9", REPO);

        assertThat(step.secretEnv()).containsExactly("GITHUB_TOKEN");
        assertThat(command(step)).contains("$$GITHUB_TOKEN");
    }

    /** The one thing that must never happen: the real token appearing in the stored build config. */
    @Test
    void neverPutsTheLiteralTokenValueInTheStepsArguments() {
        BuildService.BuildStep step = buildService.cloneStep("1.0.9", REPO);

        assertThat(command(step)).doesNotContain("ghp_realtokenvalue");
    }

    /**
     * A credentialed clone URL is written verbatim into src/.git/config as remote.origin.url, and
     * /workspace is shared with every later step — so a token in the URL outlives this one. It can
     * also be echoed into the build log when a clone fails. The header form avoids both.
     */
    @Test
    void authenticatesWithAHeaderRatherThanACredentialInTheCloneUrl() {
        BuildService.BuildStep step = buildService.cloneStep("1.0.9", REPO);

        assertThat(command(step)).contains("http.extraHeader=\"Authorization: Basic $$AUTH\"");
        assertThat(command(step)).contains("https://github.com/DurgaraoSails/poc-integration-testbed.git");
        assertThat(command(step)).doesNotContain("x-access-token:$$GITHUB_TOKEN@");
    }

    /**
     * A manifest may set context: "." — the default for a repo with no poc.yaml — which makes the
     * whole checkout the docker build context. Leaving .git there lets a COPY . . with no
     * .dockerignore bake git metadata into a published image layer.
     */
    @Test
    void removesGitMetadataSoItCannotReachADockerBuildContext() {
        BuildService.BuildStep step = buildService.cloneStep("1.0.9", REPO);

        assertThat(command(step)).contains("rm -rf src/.git");
    }

    /** Without set -e the step would carry on to `rm -rf` and exit 0 even if the clone failed. */
    @Test
    void failsTheStepWhenTheCloneFails() {
        assertThat(command(buildService.cloneStep("1.0.9", REPO))).startsWith("set -e");
    }

    @Test
    void clonesTheRequestedTagShallowly() {
        assertThat(command(buildService.cloneStep("1.0.9", REPO))).contains("clone --branch 1.0.9 --depth 1");
    }

    // --- deploy step: gcloud's flag grammar -------------------------------------------------

    /**
     * gcloud parses every flag after the first --container= as scoped to that container and
     * rejects anything it does not recognise as container-level with a usage error (exit code 2).
     * This repo has shipped that regression once already.
     */
    @Test
    void putsEveryServiceLevelFlagBeforeTheFirstContainerFlag() {
        List<String> args = buildService.deployStep("my-poc", twoContainerManifest(),
                Map.of("web", "img/web:1", "api", "img/api:1")).args();

        int firstContainer = args.indexOf("--container=web");
        assertThat(firstContainer).isPositive();
        assertThat(args.subList(0, firstContainer)).contains(
                "--region=us-central1",
                "--service-account=poc-runtime-dev@sails-agenthub.iam.gserviceaccount.com",
                "--allow-unauthenticated");
        assertThat(args.subList(firstContainer, args.size()))
                .noneMatch(arg -> arg.startsWith("--region=") || arg.startsWith("--service-account="));
    }

    @Test
    void invokesGcloudRunDeployForTheServiceSlug() {
        BuildService.BuildStep step = buildService.deployStep("my-poc", twoContainerManifest(),
                Map.of("web", "img/web:1", "api", "img/api:1"));

        assertThat(step.entrypoint()).isEqualTo("gcloud");
        assertThat(step.args().subList(0, 3)).containsExactly("run", "deploy", "my-poc");
    }

    private static PocManifest twoContainerManifest() {
        ManifestContainer web = new ManifestContainer("web", ContainerRole.INGRESS, "Dockerfile", ".", 8080, Map.of());
        ManifestContainer api = new ManifestContainer("api", ContainerRole.SIDECAR, "Dockerfile", "api", 8081, Map.of());
        return new PocManifest(List.of(web, api), new Resources(null, null));
    }

    /** The clone runs as `bash -c <command>`, so the command is the second argument. */
    private static String command(BuildService.BuildStep step) {
        assertThat(step.entrypoint()).isEqualTo("bash");
        assertThat(step.args()).hasSize(2).first().isEqualTo("-c");
        return step.args().get(1);
    }
}

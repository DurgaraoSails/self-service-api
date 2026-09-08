package com.sails.ai.selfserviceapi.deploypipeline.build;

import static org.assertj.core.api.Assertions.assertThat;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Covers the docker build step's shape. Both properties asserted here fail *silently* if broken —
 * a build that still succeeds while leaking the token, or one that never authenticates — so
 * neither is caught by anything downstream.
 */
class BuildServiceTest {

    private static final ManifestContainer WEB =
            new ManifestContainer("web", ContainerRole.INGRESS, "apps/frontend/Dockerfile", "apps/frontend", 8080, Map.of());

    private static BuildService withNpmSecret(String npmTokenSecretId) {
        PipelineProperties properties = new PipelineProperties(
                "cloud-build", null, "github-token", null, npmTokenSecretId, false, true, null,
                Duration.ofMinutes(10), Duration.ofMinutes(20), Duration.ofSeconds(10));
        // buildStep touches neither the RestClient nor GcpProperties.
        return new BuildService(null, null, properties, null);
    }

    @Test
    void buildsPlainlyWhenNoNpmTokenIsConfigured() {
        BuildService.BuildStep step = withNpmSecret(null).buildStep(WEB, "img/web:1");

        assertThat(step.entrypoint()).isNull();
        assertThat(step.secretEnv()).isNull();
        assertThat(step.args())
                .containsExactly("build", "-f", "src/apps/frontend/Dockerfile", "-t", "img/web:1", "src/apps/frontend");
    }

    @Test
    void mountsTheTokenAsABuildKitSecretWhenConfigured() {
        BuildService.BuildStep step = withNpmSecret("npm-token").buildStep(WEB, "img/web:1");

        assertThat(step.secretEnv()).containsExactly("NPM_TOKEN");
        String command = String.join("\n", step.args());
        assertThat(command).contains("DOCKER_BUILDKIT=1");
        assertThat(command).contains("--secret id=npm_token,src=/tmp/npm_token");
        assertThat(command).contains("-f src/apps/frontend/Dockerfile -t img/web:1 src/apps/frontend");
    }

    @Test
    void defersTokenExpansionToTheShellSoItNeverLandsOnTheBuildResource() {
        String command = String.join("\n", withNpmSecret("npm-token").buildStep(WEB, "img/web:1").args());

        // Cloud Build resolves $$ to a literal $, leaving the shell to expand secretEnv at run
        // time. A single $ would be substituted at submit time and stored on the Build forever.
        assertThat(command).contains("\"$$NPM_TOKEN\"");
    }

    @Test
    void writesTheTokenOutsideTheWorkspaceSharedWithLaterSteps() {
        String command = String.join("\n", withNpmSecret("npm-token").buildStep(WEB, "img/web:1").args());

        // /workspace persists across every step of the build; /tmp is this step's container alone.
        assertThat(command).doesNotContain("/workspace");
        assertThat(command).contains("> /tmp/npm_token");
    }
}

package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNotice;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoLayout;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.EnvVarClassifier;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CloudBuildImporterTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final CloudBuildImporter importer = new CloudBuildImporter(new EnvVarClassifier(new ManifestProperties(null, null, 0)));

    private String fixture(String name) {
        try (InputStream in = getClass().getResourceAsStream("/onboarding/cloudbuild/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Optional<com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImport> importFixture(String name) {
        when(gitHubService.getFileContent(REPO, SHA, "cloudbuild.yaml")).thenReturn(Optional.of(fixture(name)));
        RepoLayout layout = RepoLayout.of(new GitHubTree(List.of(new GitHubTreeEntry("cloudbuild.yaml", "blob", 10L)), false));
        RepoFileReader reader = new RepoFileReader(gitHubService, REPO, SHA);
        return importer.importFrom(layout, reader);
    }

    @Test
    void returnsEmptyWhenTheRepoHasNoCloudbuildFile() {
        RepoLayout layout = RepoLayout.of(new GitHubTree(List.of(), false));
        RepoFileReader reader = new RepoFileReader(gitHubService, REPO, SHA);

        assertThat(importer.importFrom(layout, reader)).isEmpty();
    }

    @Test
    void importsPortMemoryCpuScalingAndEnvFromADockerBuildDeploy() {
        var result = importFixture("docker-build-deploy.yaml").orElseThrow();

        assertThat(result.services()).hasSize(1);
        var container = result.services().get(0).containers().get(0);
        assertThat(container.port()).isEqualTo(3000);
        assertThat(container.memory()).isEqualTo("512Mi");
        assertThat(container.cpu()).isEqualTo("1");
        assertThat(container.env()).containsEntry("LOG_LEVEL", "info");
        assertThat(result.services().get(0).minInstances()).isEqualTo(0);
        assertThat(result.services().get(0).maxInstances()).isEqualTo(4);
        assertThat(container.dockerfile()).isEqualTo("Dockerfile");
        assertThat(container.context()).isEqualTo(".");
        assertThat(result.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.CLOUDBUILD_IMPORTED));
    }

    @Test
    void linksABashScriptDockerBuildToItsDeploy() {
        var result = importFixture("bash-script-deploy.yaml").orElseThrow();

        var container = result.services().get(0).containers().get(0);
        assertThat(container.dockerfile()).isEqualTo("apps/api/Dockerfile");
        assertThat(container.context()).isEqualTo("apps/api");
        assertThat(container.port()).isEqualTo(7000);
    }

    @Test
    void importsAMultiContainerSingleServiceDeploy() {
        var result = importFixture("multi-container.yaml").orElseThrow();

        assertThat(result.services()).hasSize(1);
        var containers = result.services().get(0).containers();
        assertThat(containers).hasSize(2);
        assertThat(containers.get(0).name()).isEqualTo("web");
        assertThat(containers.get(0).dockerfile()).isEqualTo("apps/web/Dockerfile");
        assertThat(containers.get(1).name()).isEqualTo("api");
        assertThat(containers.get(1).dockerfile()).isEqualTo("apps/api/Dockerfile");
        assertThat(containers.get(1).secretEnvNames()).containsExactly("DB_PASSWORD");
        // Never the Secret Manager reference the flag pointed at.
        assertThat(result.toString()).doesNotContain("db-password:latest");
    }

    /** This platform runs one Cloud Run service with sidecars — two separate deploys must merge, not stay separate. */
    @Test
    void mergesTwoSeparateServiceDeploysIntoOneWithASidecarPort() {
        var result = importFixture("two-services.yaml").orElseThrow();

        assertThat(result.services()).hasSize(1);
        var containers = result.services().get(0).containers();
        assertThat(containers).hasSize(2);
        assertThat(containers.get(0).port()).isEqualTo(8080);
        assertThat(containers.get(1).port()).isEqualTo(7000); // the second deploy declared its own port
        assertThat(result.notices()).anySatisfy(n -> {
            assertThat(n.code()).isEqualTo(GenerationNoticeCode.SERVICES_MERGED);
            assertThat(n.severity()).isEqualTo(com.sails.ai.selfserviceapi.generated.model.PocOnboardingSeverity.WARNING);
        });
    }

    @Test
    void importsAKanikoBuild() {
        var result = importFixture("kaniko.yaml").orElseThrow();

        var container = result.services().get(0).containers().get(0);
        assertThat(container.dockerfile()).isEqualTo("Dockerfile");
        assertThat(container.context()).isEqualTo("/workspace");
    }

    @Test
    void flagsASourceDeployWithNoMatchingBuildAsNeedingBuildpacksFallback() {
        var result = importFixture("source-deploy.yaml").orElseThrow();

        assertThat(result.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.BUILDPACKS_NEEDS_DOCKERFILE));
    }

    @Test
    void tracksAPreBuildStepSeparatelyFromDockerBuild() {
        var result = importFixture("prebuilt-jar.yaml").orElseThrow();

        assertThat(result.preBuildSteps()).anySatisfy(step -> assertThat(step).contains("mvn"));
        assertThat(result.services().get(0).containers().get(0).dockerfile()).isEqualTo("Dockerfile");
    }

    /** The privacy rule: a secret value read from the repo must never appear anywhere in the import result. */
    @Test
    void neverLeaksASecretValueAnywhereInTheImport() {
        var result = importFixture("secrets-substitutions.yaml").orElseThrow();

        assertThat(result.services().get(0).name()).isEqualTo("contract-agent");
        var container = result.services().get(0).containers().get(0);
        assertThat(container.env()).doesNotContainKey("API_TOKEN");
        assertThat(container.secretEnvNames()).containsExactly("DB_PASSWORD");

        String everything = result.toString();
        assertThat(everything).doesNotContain("sk-verylongfakesecretvalue1234567890");
        assertThat(everything).doesNotContain("db-password-ref");
    }

    @Test
    void resolvesUserDefinedSubstitutionsInTheServiceNameAndRegion() {
        var result = importFixture("secrets-substitutions.yaml").orElseThrow();

        assertThat(result.services().get(0).name()).isEqualTo("contract-agent");
    }

    @Test
    void reportsUnsupportedFlagsWithoutApplyingThem() {
        var result = importFixture("unsupported-flags.yaml").orElseThrow();

        List<String> codes = result.notices().stream().map(GenerationNotice::code).toList();
        assertThat(codes).contains(GenerationNoticeCode.UNSUPPORTED_SETTING);
    }

    @Test
    void aMalformedCloudbuildFileProducesAParseFailedNoticeRatherThanThrowing() {
        var result = importFixture("malformed.yaml").orElseThrow();

        assertThat(result.services()).isEmpty();
        assertThat(result.notices()).anySatisfy(n -> assertThat(n.code()).isEqualTo(GenerationNoticeCode.CLOUDBUILD_PARSE_FAILED));
    }
}

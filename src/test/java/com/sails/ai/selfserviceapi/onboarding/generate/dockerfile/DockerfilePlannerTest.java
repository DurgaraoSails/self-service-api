package com.sails.ai.selfserviceapi.onboarding.generate.dockerfile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.onboarding.generate.GeneratedFile;
import com.sails.ai.selfserviceapi.onboarding.generate.GenerationNoticeCode;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoFileReader;
import com.sails.ai.selfserviceapi.onboarding.generate.RepoLayout;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ModelRequest;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackDetector;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class DockerfilePlannerTest {

    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private final StackDetector stackDetector = new StackDetector();
    private final DockerfileTemplates templates = new DockerfileTemplates();
    private final DockerfileLinter linter = new DockerfileLinter();
    private final GitHubService gitHubService = mock(GitHubService.class);

    @BeforeEach
    void setUp() {
        lenient().when(gitHubService.getFileContent(any(), anyString(), anyString())).thenReturn(java.util.Optional.empty());
    }

    private DockerfilePlanner plannerWithNoModel() {
        DraftModelProperties properties = new DraftModelProperties(true, "ollama", Duration.ofSeconds(30), null, null, null);
        DockerfileDraftService draftService = new DockerfileDraftService(List.of(), properties, templates, linter, JsonMapper.builder().build());
        return new DockerfilePlanner(stackDetector, templates, linter, draftService, properties);
    }

    private DockerfilePlanner plannerWithModel(ManifestDraftModel model, int maxCalls) {
        DraftModelProperties properties = new DraftModelProperties(true, model.name(), Duration.ofSeconds(30), null, null, maxCalls);
        DockerfileDraftService draftService = new DockerfileDraftService(List.of(model), properties, templates, linter, JsonMapper.builder().build());
        return new DockerfilePlanner(stackDetector, templates, linter, draftService, properties);
    }

    private RepoFileReader fileReader() {
        return new RepoFileReader(gitHubService, REPO, SHA);
    }

    private ManifestContainer container(String dockerfile, String context) {
        return new ManifestContainer("app", ContainerRole.INGRESS, dockerfile, context, null, Map.of());
    }

    private PocManifest manifestOf(ManifestContainer... containers) {
        return new PocManifest(List.of(containers), new Resources(null, null));
    }

    @Test
    void aCleanExistingDockerfileIsKept() {
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(
                java.util.Optional.of("FROM node:20-slim\nUSER node\nCMD [\"node\", \"server.js\"]\n"));
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("Dockerfile", "blob", 10L),
                new GitHubTreeEntry(".dockerignore", "blob", 5L)), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).isEmpty();
        assertThat(result.notices()).noneMatch(n -> n.code().equals(GenerationNoticeCode.DOCKERFILE_NO_DOCKERIGNORE));
    }

    @Test
    void aKeptDockerfileWithNoDockerignoreGetsANoticeButNoFile() {
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(
                java.util.Optional.of("FROM node:20-slim\nUSER node\nCMD [\"node\", \"server.js\"]\n"));
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("Dockerfile", "blob", 10L)), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).isEmpty();
        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.DOCKERFILE_NO_DOCKERIGNORE));
    }

    @Test
    void anExistingDockerfileThatDependsOnAnExternalBuildArtifactIsReplaced() {
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(
                java.util.Optional.of("FROM eclipse-temurin:21-jre\nCOPY target/app.jar app.jar\nCMD [\"java\", \"-jar\", \"app.jar\"]\n"));
        when(gitHubService.getFileContent(REPO, SHA, "pom.xml")).thenReturn(java.util.Optional.of("<project></project>"));
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("Dockerfile", "blob", 10L),
                new GitHubTreeEntry("pom.xml", "blob", 10L)), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.DOCKERFILE_EXTERNAL_ARTIFACT));
        assertThat(result.files()).anyMatch(f -> f.kind() == GeneratedFile.Kind.DOCKERFILE
                && f.action() == GeneratedFile.Action.REPLACE && f.source() == GeneratedFile.Source.TEMPLATE);
        assertThat(result.files()).anyMatch(f -> f.kind() == GeneratedFile.Kind.DOCKERIGNORE);
    }

    @Test
    void aMissingDockerfileForARecognizedStackIsCreatedFromATemplateWithADockerignore() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(java.util.Optional.of("{\"dependencies\":{\"express\":\"^4.18.0\"}}"));
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("package.json", "blob", 10L)), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).hasSize(2);
        assertThat(result.files()).anyMatch(f -> f.kind() == GeneratedFile.Kind.DOCKERFILE && f.action() == GeneratedFile.Action.CREATE);
        assertThat(result.files()).anyMatch(f -> f.kind() == GeneratedFile.Kind.DOCKERIGNORE);
    }

    @Test
    void aMissingDockerignoreIsNotGeneratedWhenOneAlreadyExistsInTheDirectory() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(java.util.Optional.of("{\"dependencies\":{\"express\":\"^4.18.0\"}}"));
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("package.json", "blob", 10L),
                new GitHubTreeEntry(".dockerignore", "blob", 5L)), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).noneMatch(f -> f.kind() == GeneratedFile.Kind.DOCKERIGNORE);
        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.FILE_EXISTS_NOT_OVERWRITTEN));
    }

    @Test
    void aRecognizedNginxServedSpaStackAlsoGetsTheSharedNginxConfig() {
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(java.util.Optional.of("{\"dependencies\":{\"vite\":\"^5.0.0\"}}"));
        GitHubTree tree = new GitHubTree(List.of(new GitHubTreeEntry("package.json", "blob", 10L)), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).anyMatch(f -> f.kind() == GeneratedFile.Kind.CONFIG
                && f.path().equals("nginx-default.conf.template"));
    }

    @Test
    void anUnrecognizedStackWithNoModelReachableIsReportedAsUnresolved() {
        GitHubTree tree = new GitHubTree(List.of(), false);
        RepoLayout layout = RepoLayout.of(tree);

        DockerfilePlanner.PlanResult result = plannerWithNoModel().plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).isEmpty();
        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.DOCKERFILE_UNRESOLVED));
    }

    @Test
    void anUnrecognizedStackAsksTheModelAndRendersWhateverDockerfileItReturns() {
        GitHubTree tree = new GitHubTree(List.of(), false);
        RepoLayout layout = RepoLayout.of(tree);
        String modelDockerfile = "FROM alpine:3.20\nUSER nobody\nCMD sleep 1\n";
        String responseJson = "{\"stack\":\"OTHER\",\"dockerfile\":\"" + modelDockerfile.replace("\n", "\\n")
                + "\",\"reason\":\"custom\",\"assumptions\":[]}";
        ManifestDraftModel model = stubModel("stub", responseJson);

        DockerfilePlanner.PlanResult result = plannerWithModel(model, 2).plan(manifestOf(container("Dockerfile", ".")), layout, fileReader());

        assertThat(result.files()).singleElement().satisfies(f -> {
            assertThat(f.source()).isEqualTo(GeneratedFile.Source.MODEL);
            assertThat(f.action()).isEqualTo(GeneratedFile.Action.CREATE);
            assertThat(f.needsReview()).isTrue();
        });
    }

    @Test
    void theModelCallBudgetIsSharedAcrossContainersInOneRun() {
        GitHubTree tree = new GitHubTree(List.of(), false);
        RepoLayout layout = RepoLayout.of(tree);
        ManifestDraftModel model = stubModel("stub", "{\"stack\":\"OTHER\",\"dockerfile\":\"\",\"reason\":\"\",\"assumptions\":[]}");
        ManifestContainer first = new ManifestContainer("first", ContainerRole.INGRESS, "apps/a/Dockerfile", "apps/a", null, Map.of());
        ManifestContainer second = new ManifestContainer("second", ContainerRole.SIDECAR, "apps/b/Dockerfile", "apps/b", 8081, Map.of());

        DockerfilePlanner.PlanResult result = plannerWithModel(model, 1).plan(manifestOf(first, second), layout, fileReader());

        // Both containers end up unresolved (the stub model returns a blank dockerfile), but only the
        // second is turned away purely for lack of budget — the first still spent its one call.
        long unresolved = result.notices().stream().filter(n -> n.code().equals(GenerationNoticeCode.DOCKERFILE_UNRESOLVED)).count();
        assertThat(unresolved).isEqualTo(2);
        assertThat(result.notices()).anyMatch(n -> n.message().contains("budget"));
    }

    private ManifestDraftModel stubModel(String name, String response) {
        return new ManifestDraftModel() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String draft(ModelRequest request) {
                return response;
            }
        };
    }
}

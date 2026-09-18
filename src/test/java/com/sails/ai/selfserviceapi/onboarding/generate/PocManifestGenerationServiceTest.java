package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.config.PocRuntimeProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService.RepoAccess;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestParser;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestProperties;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidator;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.onboarding.OnboardingCheckResult;
import com.sails.ai.selfserviceapi.onboarding.PocOnboardingCheckService;
import com.sails.ai.selfserviceapi.onboarding.generate.PocManifestGenerationResult.Outcome;
import com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild.CloudBuildImporter;
import com.sails.ai.selfserviceapi.onboarding.generate.dockerfile.DockerfileDraftService;
import com.sails.ai.selfserviceapi.onboarding.generate.dockerfile.DockerfileLinter;
import com.sails.ai.selfserviceapi.onboarding.generate.dockerfile.DockerfilePlanner;
import com.sails.ai.selfserviceapi.onboarding.generate.dockerfile.DockerfileTemplates;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftException;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import com.sails.ai.selfserviceapi.onboarding.generate.secret.EnvVarClassifier;
import com.sails.ai.selfserviceapi.onboarding.generate.stack.StackDetector;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every deterministic collaborator ({@code ManifestParser}, {@code ManifestValidator},
 * {@code CloudBuildImporter}, {@code StackDetector}, {@code ManifestMerger},
 * {@code DeterministicContainerPlanner}, {@code DockerfileTemplates}, {@code DockerfileLinter},
 * {@code DockerfilePlanner}, {@code ManifestYamlWriter}) runs for real here — each already has its
 * own unit tests, and using the real thing means a mocked poc.yaml never has to be hand-crafted to
 * survive this class's own re-validation step. Only {@code GitHubService}, {@code ManifestService},
 * {@code PocOnboardingCheckService}, {@code RepoInventoryService} and the manifest-level
 * {@code ManifestDraftService} (the one LLM-backed collaborator that matters for these tests) are
 * mocked.
 */
class PocManifestGenerationServiceTest {

    private static final String URL = "https://github.com/acme/contract-agent";
    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String BRANCH = "main";
    private static final String SHA = "abc123";

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final ManifestService manifestService = mock(ManifestService.class);
    private final PocOnboardingCheckService checkService = mock(PocOnboardingCheckService.class);
    private final RepoInventoryService inventoryService = mock(RepoInventoryService.class);
    private final ManifestDraftService draftService = mock(ManifestDraftService.class);

    private final ManifestParser manifestParser = new ManifestParser();
    private final ManifestValidator manifestValidator =
            new ManifestValidator(new ManifestProperties(null, null, 0), new PocRuntimeProperties(null, null, null));
    private final CloudBuildImporter cloudBuildImporter =
            new CloudBuildImporter(new EnvVarClassifier(new ManifestProperties(null, null, 0)));
    private final StackDetector stackDetector = new StackDetector();
    private final InfrastructureDetector infrastructureDetector = new InfrastructureDetector();
    private final ManifestMerger manifestMerger = new ManifestMerger();
    private final DeterministicContainerPlanner deterministicPlanner = new DeterministicContainerPlanner();
    private final DockerfileTemplates templates = new DockerfileTemplates();
    private final DockerfileLinter linter = new DockerfileLinter();
    private final ManifestYamlWriter yamlWriter = new ManifestYamlWriter();

    private DraftModelProperties properties;
    private PocManifestGenerationService service;

    @BeforeEach
    void setUp() {
        properties = new DraftModelProperties(true, "ollama", Duration.ofSeconds(60), null, null, null);
        service = buildService(properties);
        when(checkService.check(URL, BRANCH, null))
                .thenReturn(new OnboardingCheckResult("acme/contract-agent", false, List.of(), List.of()));
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.OK);
        when(gitHubService.getBranchHeadSha(REPO, BRANCH)).thenReturn(SHA);
        lenient().when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf());
        // Every file lookup this class's collaborators make (CloudBuildImporter, StackDetector,
        // DockerfilePlanner) goes through RepoFileReader -> this same GitHubService call — default
        // to "not found" so an un-stubbed path never NPEs on a null Optional.
        lenient().when(gitHubService.getFileContent(any(), anyString(), anyString())).thenReturn(Optional.empty());
    }

    private PocManifestGenerationService buildService(DraftModelProperties props) {
        DockerfileDraftService dockerfileDraftService =
                new DockerfileDraftService(List.of(), props, templates, linter, JsonMapper.builder().build());
        DockerfilePlanner dockerfilePlanner = new DockerfilePlanner(stackDetector, templates, linter, dockerfileDraftService, props);
        return new PocManifestGenerationService(gitHubService, manifestService, manifestParser, manifestValidator,
                checkService, inventoryService, cloudBuildImporter, stackDetector, infrastructureDetector, draftService,
                manifestMerger, deterministicPlanner, dockerfilePlanner, yamlWriter, props);
    }

    private GitHubTree treeOf(String... blobPaths) {
        return new GitHubTree(
                java.util.Arrays.stream(blobPaths).map(p -> new GitHubTreeEntry(p, "blob", 10L)).toList(), false);
    }

    @Test
    void aSingleRootDockerfileNeedsNoManifest() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("Dockerfile", "README.md"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_NEEDED);
        assertThat(result.pocYaml()).isNull();
        verify(draftService, never()).draft(any(), any(), any(), any());
    }

    @Test
    void anAlreadyValidManifestWithItsDockerfileInPlaceNeedsNoRegeneration() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("Dockerfile"));
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile"))
                .thenReturn(Optional.of("FROM node:20-slim\nUSER node\nCMD [\"node\", \"server.js\"]\n"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution("containers:\n  - name: app\n", singleContainerManifest()));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_NEEDED);
        verify(inventoryService, never()).inventory(any(), any());
        verify(draftService, never()).draft(any(), any(), any(), any());
    }

    /** A valid poc.yaml is never rewritten — only the Dockerfile it names but doesn't have is generated. */
    @Test
    void aValidManifestNamingAMissingDockerfileGeneratesJustThatFile() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("package.json"));
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"dependencies\":{\"express\":\"^4.18.0\"}}"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution("containers:\n  - name: app\n", singleContainerManifest()));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.GENERATED);
        assertThat(result.pocYaml()).isNull();
        assertThat(result.dockerfiles()).anyMatch(f -> f.path().equals("Dockerfile"));
        verify(draftService, never()).draft(any(), any(), any(), any());
    }

    @Test
    void generatesAFreshManifestWhenNoneExistsAndItIsNotASingleContainerRepo() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        RepoInventory inventory = new RepoInventory(
                treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"), List.of(), false);
        when(inventoryService.inventory(REPO, SHA)).thenReturn(inventory);

        ManifestDraftModel model = availableModel();
        when(draftService.selectedModel()).thenReturn(Optional.of(model));
        ManifestDraftResult draftResult = new ManifestDraftResult(singleContainerManifest(), List.of("assumed things"), List.of());
        when(draftService.draft(eq(inventory), any(ManifestFacts.class), isNull(), any())).thenReturn(draftResult);

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.GENERATED);
        assertThat(result.pocYaml()).contains("name: app");
        assertThat(result.manifestWasCorrected()).isFalse();
        assertThat(result.assumptions()).containsExactly("assumed things");
    }

    /**
     * Covers all three paths in one place — model-drafted here, deterministic-fallback and
     * cloudbuild-imported are covered by ManifestMergerTest/DeterministicContainerPlannerTest
     * exercising the same requires: list through ManifestContainer directly.
     */
    @Test
    void everySecretRequirementGetsAProvisioningInstructionNotice() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        RepoInventory inventory = new RepoInventory(
                treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"), List.of(), false);
        when(inventoryService.inventory(REPO, SHA)).thenReturn(inventory);

        PocManifest manifestWithSecret = new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of(), null, null,
                        List.of(new com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestRequirement("OPENAI_API_KEY", true)))),
                new Resources(null, null));
        when(draftService.selectedModel()).thenReturn(Optional.of(availableModel()));
        ManifestDraftResult draftResult = new ManifestDraftResult(manifestWithSecret, List.of(), List.of());
        when(draftService.draft(eq(inventory), any(ManifestFacts.class), isNull(), any())).thenReturn(draftResult);

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.SECRET_PROVISIONING_INSTRUCTIONS)
                && n.message().contains("OPENAI_API_KEY") && "app".equals(n.container()));
    }

    /** A poc.yaml exists but the checker's own validator rejects it — correct it, not replace it blind. */
    @Test
    void correctsAnInvalidExistingManifestInsteadOfIgnoringIt() {
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenThrow(new ManifestValidationException(List.of("exactly one container must have role 'ingress'")));
        when(gitHubService.getFileContent(REPO, SHA, "poc.yaml")).thenReturn(Optional.of("containers:\n  - name: broken\n"));
        RepoInventory inventory = new RepoInventory(treeOf(), List.of(), false);
        when(inventoryService.inventory(REPO, SHA)).thenReturn(inventory);

        ManifestDraftModel model = availableModel();
        when(draftService.selectedModel()).thenReturn(Optional.of(model));
        ManifestDraftResult draftResult = new ManifestDraftResult(singleContainerManifest(), List.of(), List.of());
        when(draftService.draft(eq(inventory), any(ManifestFacts.class), eq("containers:\n  - name: broken\n"), any()))
                .thenReturn(draftResult);

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.GENERATED);
        assertThat(result.manifestWasCorrected()).isTrue();
    }

    @Test
    void generationDisabledIsUnavailableWithoutTouchingGitHub() {
        DraftModelProperties disabled = new DraftModelProperties(false, "ollama", Duration.ofSeconds(60), null, null, null);
        service = buildService(disabled);

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.pocYaml()).contains("poc.yaml");
        verify(gitHubService, never()).parseRepoUrl(anyString());
    }

    /** No model, and a two-component repo the deterministic fallback can't resolve on its own either. */
    @Test
    void noModelReachableAndNoDeterministicFallbackFallsBackToTheShippedTemplate() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(draftService.selectedModel()).thenReturn(Optional.empty());

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.pocYaml()).contains("yaml-language-server");
    }

    /** No model, but a single-component repo (a component at the repo root) the deterministic fallback CAN resolve on its own. */
    @Test
    void noModelReachableButASingleComponentRepoStillGeneratesViaTheDeterministicFallback() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("package.json"));
        when(gitHubService.getFileContent(REPO, SHA, "package.json"))
                .thenReturn(Optional.of("{\"scripts\":{\"start\":\"node index.js\"}}"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(draftService.selectedModel()).thenReturn(Optional.empty());

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.GENERATED);
        assertThat(result.notices()).anyMatch(n -> n.code().equals(GenerationNoticeCode.MODEL_UNAVAILABLE));
        assertThat(result.pocYaml()).contains("name: app");
    }

    /**
     * The provider looked available (Ollama's probe can flip between the check and the real call;
     * Vertex's only checks that a project id is configured, not that credentials work) but the real
     * call still failed — a local checkout with neither model actually reachable is exactly this
     * case, and it must degrade the same way "no model configured" does, never fail the page.
     */
    @Test
    void aDraftModelCallFailureFallsBackToTheShippedTemplateWhenTheDeterministicFallbackAlsoCannotResolveIt() {
        when(gitHubService.listTree(REPO, SHA)).thenReturn(treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"));
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        RepoInventory inventory = new RepoInventory(
                treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"), List.of(), false);
        when(inventoryService.inventory(REPO, SHA)).thenReturn(inventory);
        when(draftService.selectedModel()).thenReturn(Optional.of(availableModel()));
        when(draftService.draft(eq(inventory), any(ManifestFacts.class), isNull(), any()))
                .thenThrow(new ManifestDraftException("Connection refused"));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.pocYaml()).contains("yaml-language-server");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("Connection refused"));
    }

    /**
     * A GitHub read past the initial branch-head check (the tree scan, resolveForBuild, any
     * evidence read) can still fail transiently — a rate limit, a momentary 5xx. This used to
     * escape as this endpoint's own 502; it must degrade the same way an unreachable draft model
     * does instead.
     */
    @Test
    void aGitHubFailureMidGenerationDegradesToUnavailableRatherThanEscaping() {
        when(gitHubService.listTree(REPO, SHA)).thenThrow(new GitHubApiException("rate limited"));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.pocYaml()).contains("yaml-language-server");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("rate limited"));
    }

    @Test
    void repositoryAccessProblemsSkipGenerationEntirely() {
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.NO_PUSH);

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        verify(manifestService, never()).resolveForBuild(any(), any());
    }

    @Test
    void aBranchThatDoesNotExistSkipsGenerationEntirely() {
        when(gitHubService.getBranchHeadSha(REPO, BRANCH)).thenThrow(new GitHubApiException("Not Found"));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
    }

    private ManifestDraftModel availableModel() {
        return new ManifestDraftModel() {
            @Override
            public String name() {
                return "ollama";
            }

            @Override
            public boolean isAvailable() {
                return true;
            }

            @Override
            public String draft(com.sails.ai.selfserviceapi.onboarding.generate.model.ModelRequest request) {
                throw new UnsupportedOperationException("not used directly — draftService is mocked");
            }
        };
    }

    private PocManifest singleContainerManifest() {
        return new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of())),
                new Resources(null, null));
    }
}

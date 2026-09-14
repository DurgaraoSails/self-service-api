package com.sails.ai.selfserviceapi.onboarding.generate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService.RepoAccess;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTree;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubTreeEntry;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.onboarding.OnboardingCheckResult;
import com.sails.ai.selfserviceapi.onboarding.PocOnboardingCheckService;
import com.sails.ai.selfserviceapi.onboarding.generate.PocManifestGenerationResult.Outcome;
import com.sails.ai.selfserviceapi.onboarding.generate.model.DraftModelProperties;
import com.sails.ai.selfserviceapi.onboarding.generate.model.ManifestDraftModel;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
    private final ManifestYamlWriter yamlWriter = mock(ManifestYamlWriter.class);

    private DraftModelProperties properties;
    private PocManifestGenerationService service;

    @BeforeEach
    void setUp() {
        properties = new DraftModelProperties(true, "ollama", Duration.ofSeconds(60), null, null);
        service = new PocManifestGenerationService(
                gitHubService, manifestService, checkService, inventoryService, draftService, yamlWriter, properties);
        when(checkService.check(URL, BRANCH, null))
                .thenReturn(new OnboardingCheckResult("acme/contract-agent", false, List.of(), List.of()));
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.OK);
        when(gitHubService.getBranchHeadSha(REPO, BRANCH)).thenReturn(SHA);
    }

    private GitHubTree treeOf(String... blobPaths) {
        return new GitHubTree(
                java.util.Arrays.stream(blobPaths).map(p -> new GitHubTreeEntry(p, "blob", 10L)).toList(), false);
    }

    @Test
    void aSingleRootDockerfileNeedsNoManifest() {
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(inventoryService.inventory(REPO, SHA))
                .thenReturn(new RepoInventory(treeOf("Dockerfile", "README.md"), List.of(), false));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_NEEDED);
        assertThat(result.pocYaml()).isNull();
        verify(draftService, never()).draft(any(), any());
    }

    @Test
    void anAlreadyValidManifestNeedsNoRegeneration() {
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution("containers:\n  - name: app\n", singleContainerManifest()));

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.NOT_NEEDED);
        verify(inventoryService, never()).inventory(any(), any());
        verify(draftService, never()).draft(any(), any());
    }

    @Test
    void generatesAFreshManifestWhenNoneExistsAndItIsNotASingleContainerRepo() {
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        RepoInventory inventory = new RepoInventory(
                treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"), List.of(), false);
        when(inventoryService.inventory(REPO, SHA)).thenReturn(inventory);

        ManifestDraftModel model = availableModel();
        when(draftService.selectedModel()).thenReturn(Optional.of(model));
        ManifestDraftResult draftResult = new ManifestDraftResult(
                singleContainerManifest(), List.of(), List.of("assumed things"), List.of());
        when(draftService.draft(inventory, null)).thenReturn(draftResult);
        when(yamlWriter.write(draftResult.manifest(), draftResult.assumptions())).thenReturn("containers:\n  - name: app\n");

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.GENERATED);
        assertThat(result.pocYaml()).isEqualTo("containers:\n  - name: app\n");
        assertThat(result.manifestWasCorrected()).isFalse();
        assertThat(result.assumptions()).containsExactly("assumed things");
    }

    /** A poc.yaml exists but the checker's own validator rejects it — correct it, not replace it blind. */
    @Test
    void correctsAnInvalidExistingManifestInsteadOfIgnoringIt() {
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenThrow(new ManifestValidationException(List.of("exactly one container must have role 'ingress'")));
        when(gitHubService.getFileContent(REPO, SHA, "poc.yaml")).thenReturn(Optional.of("containers:\n  - name: broken\n"));
        RepoInventory inventory = new RepoInventory(treeOf("Dockerfile"), List.of(), false);
        when(inventoryService.inventory(REPO, SHA)).thenReturn(inventory);

        ManifestDraftModel model = availableModel();
        when(draftService.selectedModel()).thenReturn(Optional.of(model));
        ManifestDraftResult draftResult = new ManifestDraftResult(
                singleContainerManifest(), List.of(), List.of(), List.of());
        when(draftService.draft(inventory, "containers:\n  - name: broken\n")).thenReturn(draftResult);
        when(yamlWriter.write(any(), any())).thenReturn("containers:\n  - name: app\n");

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.GENERATED);
        assertThat(result.manifestWasCorrected()).isTrue();
    }

    @Test
    void generationDisabledIsUnavailableWithoutTouchingGitHub() {
        DraftModelProperties disabled = new DraftModelProperties(false, "ollama", Duration.ofSeconds(60), null, null);
        service = new PocManifestGenerationService(
                gitHubService, manifestService, checkService, inventoryService, draftService, yamlWriter, disabled);

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.pocYaml()).contains("poc.yaml");
        verify(gitHubService, never()).parseRepoUrl(anyString());
    }

    @Test
    void noModelReachableFallsBackToTheShippedTemplate() {
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(inventoryService.inventory(REPO, SHA))
                .thenReturn(new RepoInventory(treeOf("apps/web/Dockerfile", "apps/api/Dockerfile"), List.of(), false));
        when(draftService.selectedModel()).thenReturn(Optional.empty());

        PocManifestGenerationResult result = service.generate(URL, BRANCH);

        assertThat(result.outcome()).isEqualTo(Outcome.UNAVAILABLE);
        assertThat(result.pocYaml()).contains("yaml-language-server");
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

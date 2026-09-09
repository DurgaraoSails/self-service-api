package com.sails.ai.selfserviceapi.onboarding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubApiException;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService.RepoAccess;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingCheckId;
import com.sails.ai.selfserviceapi.generated.model.PocOnboardingSeverity;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PocOnboardingCheckServiceTest {

    private static final String URL = "https://github.com/acme/contract-agent";
    private static final GitHubRepoRef REPO = new GitHubRepoRef("acme", "contract-agent");
    private static final String SHA = "abc123";

    private final GitHubService gitHubService = mock(GitHubService.class);
    private final ManifestService manifestService = mock(ManifestService.class);
    private final PocRepository pocRepository = mock(PocRepository.class);

    private final PocOnboardingCheckService service =
            new PocOnboardingCheckService(gitHubService, manifestService, pocRepository);

    /**
     * The checker's whole reason to exist: the violations a team is shown are the ones
     * {@code ManifestValidator} produced, not a second list maintained beside it. Every violation
     * becomes its own finding so a manifest with three problems reads as three things to fix.
     */
    @Test
    void reportsEveryValidatorViolationAsItsOwnFinding() {
        reachableRepo();
        when(manifestService.resolveForBuild(REPO, SHA)).thenThrow(new ManifestValidationException(List.of(
                "sidecar container 'api' must declare a port — it's only reachable at an address the ingress names",
                "exactly one container must have role 'ingress' — found 2")));

        OnboardingCheckResult result = service.check(URL, null, null);

        assertThat(result.ready()).isFalse();
        assertThat(result.findings())
                .extracting(OnboardingFinding::checkId, OnboardingFinding::detail)
                .containsExactly(
                        tuple(PocOnboardingCheckId.MANIFEST_VALIDATION,
                                "sidecar container 'api' must declare a port — it's only reachable at an address the ingress names"),
                        tuple(PocOnboardingCheckId.MANIFEST_VALIDATION,
                                "exactly one container must have role 'ingress' — found 2"));
    }

    /**
     * A repo with no poc.yaml is a complete, supported setup, so it must not read as a failure.
     * It gets an INFO finding stating the assumption instead.
     */
    @Test
    void reportsTheSynthesizedDefaultAsInformationNotAsAProblem() {
        reachableRepo();
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(Optional.of("FROM scratch"));

        OnboardingCheckResult result = service.check(URL, null, null);

        assertThat(result.ready()).isTrue();
        assertThat(result.manifestPresent()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.checkId()).isEqualTo(PocOnboardingCheckId.MANIFEST_ABSENT);
                    assertThat(finding.severity()).isEqualTo(PocOnboardingSeverity.INFO);
                });
    }

    /** The check the validator cannot make, because it needs the repository rather than the manifest. */
    @Test
    void failsWhenADeclaredDockerfileDoesNotExistInTheRepo() {
        reachableRepo();
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution("containers: []", singleContainerManifest()));
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(Optional.empty());

        OnboardingCheckResult result = service.check(URL, null, null);

        assertThat(result.ready()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.checkId()).isEqualTo(PocOnboardingCheckId.DOCKERFILE_PRESENT));
    }

    /**
     * A sidecar with no health path deploys, so this must be a warning: reporting it as an error
     * would block a manifest the platform accepts, and teaching teams the checker is wrong is worse
     * than the 502 it warns about.
     */
    @Test
    void warnsButStaysReadyWhenASidecarDeclaresNoHealthPath() {
        reachableRepo();
        PocManifest manifest = new PocManifest(List.of(
                new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of(), "/healthz"),
                new ManifestContainer("api", ContainerRole.SIDECAR, "api/Dockerfile", "api", 7000, Map.of(), null)),
                new Resources(null, null));
        when(manifestService.resolveForBuild(REPO, SHA)).thenReturn(new ManifestResolution("yaml", manifest));
        when(gitHubService.getFileContent(eq(REPO), eq(SHA), anyString())).thenReturn(Optional.of("FROM scratch"));

        OnboardingCheckResult result = service.check(URL, null, null);

        assertThat(result.ready()).isTrue();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> {
                    assertThat(finding.checkId()).isEqualTo(PocOnboardingCheckId.SIDECAR_HEALTH);
                    assertThat(finding.severity()).isEqualTo(PocOnboardingSeverity.WARNING);
                });
    }

    /**
     * A public repo the token can read but not push to is the single most confusing onboarding
     * failure, because everything looks fine until the tag step. It is also the point at which
     * reading the manifest stops being useful, so the check stops there rather than piling on.
     */
    @Test
    void stopsAtRepositoryAccessAndNeverReadsTheManifest() {
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.NO_PUSH);

        OnboardingCheckResult result = service.check(URL, null, null);

        assertThat(result.ready()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.checkId()).isEqualTo(PocOnboardingCheckId.REPO_PUSH_ACCESS));
        verifyNoInteractions(manifestService);
    }

    @Test
    void reportsAnArchivedRepositoryDistinctlyFromAPermissionProblem() {
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.ARCHIVED);

        OnboardingCheckResult result = service.check(URL, null, null);

        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.checkId()).isEqualTo(PocOnboardingCheckId.REPO_ARCHIVED));
    }

    @Test
    void reportsAnUnparseableUrlWithoutCallingGitHubAtAll() {
        when(gitHubService.parseRepoUrl("not-a-url")).thenThrow(new GitHubApiException("Not a recognizable GitHub repo URL: not-a-url"));

        OnboardingCheckResult result = service.check("not-a-url", null, null);

        assertThat(result.ready()).isFalse();
        assertThat(result.findings()).singleElement()
                .satisfies(finding -> assertThat(finding.checkId()).isEqualTo(PocOnboardingCheckId.REPO_URL));
        verifyNoInteractions(manifestService, pocRepository);
    }

    /** A slug collision is a 409 at creation time, which is late to discover it. */
    @Test
    void reportsATakenSlug() {
        reachableRepo();
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(Optional.of("FROM scratch"));
        when(pocRepository.findBySlugAndDeletedAtIsNull("contract-agent")).thenReturn(Optional.of(new Poc()));

        OnboardingCheckResult result = service.check(URL, null, "contract-agent");

        assertThat(result.ready()).isFalse();
        assertThat(result.findings())
                .extracting(OnboardingFinding::checkId)
                .contains(PocOnboardingCheckId.SLUG_AVAILABLE);
    }

    @Test
    void skipsTheSlugCheckEntirelyWhenNoSlugIsSupplied() {
        reachableRepo();
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution(null, singleContainerManifest()));
        when(gitHubService.getFileContent(REPO, SHA, "Dockerfile")).thenReturn(Optional.of("FROM scratch"));

        service.check(URL, null, "  ");

        verifyNoInteractions(pocRepository);
    }

    private void reachableRepo() {
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.OK);
        // null: no branch supplied means the repository's own default, which is what a POC with
        // none configured would deploy from.
        when(gitHubService.getDeployBranchHeadSha(REPO, null)).thenReturn(SHA);
    }

    // --- the branch the team says they will deploy from ---------------------------------------

    /**
     * poc.yaml and the Dockerfiles it names are read at the head of the branch being checked, so a
     * repository whose default branch is ready says nothing about the feature branch a team is
     * actually about to onboard.
     */
    @Test
    void checksTheBranchTheTeamNamedRatherThanTheDefault() {
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.OK);
        when(gitHubService.getDeployBranchHeadSha(REPO, "release/2024")).thenReturn(SHA);
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution("containers:\n", singleContainerManifest()));
        when(gitHubService.getFileContent(eq(REPO), eq(SHA), anyString())).thenReturn(Optional.of("FROM scratch"));

        OnboardingCheckResult result = service.check(URL, "release/2024", null);

        assertThat(result.ready()).isTrue();
        verify(gitHubService).getDeployBranchHeadSha(REPO, "release/2024");
    }

    @Test
    void treatsABlankBranchAsNotSupplied() {
        reachableRepo();
        when(manifestService.resolveForBuild(REPO, SHA))
                .thenReturn(new ManifestResolution("containers:\n", singleContainerManifest()));
        when(gitHubService.getFileContent(eq(REPO), eq(SHA), anyString())).thenReturn(Optional.of("FROM scratch"));

        service.check(URL, "   ", null);

        verify(gitHubService).getDeployBranchHeadSha(REPO, null);
    }

    /**
     * Rejected here rather than sent to GitHub: the name is concatenated into the URI path, so
     * ".." would climb out of the repository being asked about.
     */
    @Test
    void reportsABranchNameGitWouldNotAcceptInsteadOfAskingGitHub() {
        when(gitHubService.parseRepoUrl(URL)).thenReturn(REPO);
        when(gitHubService.checkPushAccess(REPO)).thenReturn(RepoAccess.OK);

        OnboardingCheckResult result = service.check(URL, "../../other/repo", null);

        assertThat(result.ready()).isFalse();
        assertThat(result.findings()).anySatisfy(finding ->
                assertThat(finding.title()).contains("not a usable git branch name"));
        verify(gitHubService, never()).getDeployBranchHeadSha(any(), any());
    }

    private PocManifest singleContainerManifest() {
        return new PocManifest(
                List.of(new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of())),
                new Resources(null, null));
    }
}

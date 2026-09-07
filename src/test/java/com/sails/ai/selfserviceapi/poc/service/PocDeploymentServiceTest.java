package com.sails.ai.selfserviceapi.poc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ContainerRole;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.Resources;
import com.sails.ai.selfserviceapi.poc.deployment.BuildAndDeployRequest;
import com.sails.ai.selfserviceapi.poc.deployment.DeploymentTrigger;
import com.sails.ai.selfserviceapi.poc.deployment.RedeployRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
import com.sails.ai.selfserviceapi.poc.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocVersionContainerRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;

class PocDeploymentServiceTest {

    private PocRepository pocRepository;
    private PocVersionRepository pocVersionRepository;
    private PocDeploymentRepository pocDeploymentRepository;
    private PocVersionContainerRepository pocVersionContainerRepository;
    private DeploymentTrigger deploymentTrigger;
    private PipelineProperties pipelineProperties;
    private GitHubService gitHubService;
    private ManifestService manifestService;
    private PocDeploymentService service;

    @BeforeEach
    void setUp() {
        pocRepository = Mockito.mock(PocRepository.class);
        pocVersionRepository = Mockito.mock(PocVersionRepository.class);
        pocDeploymentRepository = Mockito.mock(PocDeploymentRepository.class);
        pocVersionContainerRepository = Mockito.mock(PocVersionContainerRepository.class);
        deploymentTrigger = Mockito.mock(DeploymentTrigger.class);
        pipelineProperties = Mockito.mock(PipelineProperties.class);
        gitHubService = Mockito.mock(GitHubService.class);
        manifestService = Mockito.mock(ManifestService.class);
        service = new PocDeploymentService(pocRepository, pocVersionRepository, pocDeploymentRepository,
                pocVersionContainerRepository, deploymentTrigger, pipelineProperties, gitHubService,
                manifestService, new ObjectMapper());

        when(pocVersionRepository.save(any(PocVersion.class))).thenAnswer(invocation -> {
            PocVersion version = invocation.getArgument(0);
            if (version.getId() == null) {
                version.setId(100L);
            }
            return version;
        });
        when(pocDeploymentRepository.save(any(PocDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Most tests below predate manifest support and only care about versioning/deployment
        // bookkeeping — skip mode keeps deployNewVersion/retryDeployment from touching GitHub at
        // all, exactly like a real skip-mode deploy. Tests that exercise manifest resolution
        // override this.
        when(pipelineProperties.isSkip()).thenReturn(true);
        when(manifestService.resolveStored(any())).thenReturn(defaultManifest());
    }

    private static Poc pocWithGithubUrl(Long id) {
        Poc poc = new Poc();
        poc.setId(id);
        poc.setName("Contract Agent");
        poc.setGithubUrl("https://github.com/example-org/contract-agent");
        poc.setSlug("contract-agent");
        return poc;
    }

    private static PocManifest defaultManifest() {
        ManifestContainer app = new ManifestContainer("app", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        return new PocManifest(List.of(app), new Resources(null, null));
    }

    private static PocManifest twoContainerManifest() {
        ManifestContainer api = new ManifestContainer("api", ContainerRole.INGRESS, "Dockerfile", ".", null, Map.of());
        ManifestContainer worker = new ManifestContainer("worker", ContainerRole.SIDECAR, "worker/Dockerfile", "worker", 9000, Map.of());
        return new PocManifest(List.of(api, worker), new Resources(null, null));
    }

    @Test
    void deployNewVersionAllocatesVersionOneZeroOneForAPocWithNoPriorVersions() {
        Poc poc = pocWithGithubUrl(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        when(pocVersionRepository.findTopByPocIdOrderByMajorDescMinorDescPatchDesc(1L)).thenReturn(Optional.empty());

        PocDeployment deployment = service.deployNewVersion(1L, "admin-1");

        ArgumentCaptor<PocVersion> versionCaptor = ArgumentCaptor.forClass(PocVersion.class);
        verify(pocVersionRepository).save(versionCaptor.capture());
        PocVersion version = versionCaptor.getValue();
        assertThat(version.getMajor()).isEqualTo(1);
        assertThat(version.getMinor()).isEqualTo(0);
        assertThat(version.getPatch()).isEqualTo(1);
        assertThat(version.getVersionLabel()).isEqualTo("1.0.1");

        assertThat(deployment.getKind()).isEqualTo("BUILD_AND_DEPLOY");
        assertThat(deployment.getStatus()).isEqualTo("PENDING");
        assertThat(deployment.getInitiatedBy()).isEqualTo("admin-1");

        ArgumentCaptor<BuildAndDeployRequest> requestCaptor = ArgumentCaptor.forClass(BuildAndDeployRequest.class);
        verify(deploymentTrigger).buildAndDeploy(requestCaptor.capture());
        assertThat(requestCaptor.getValue().githubUrl()).isEqualTo("https://github.com/example-org/contract-agent");
        assertThat(requestCaptor.getValue().versionLabel()).isEqualTo("1.0.1");
    }

    @Test
    void deployNewVersionIncrementsThePatchWhenBelowTheCap() {
        Poc poc = pocWithGithubUrl(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        PocVersion existing = versionOf(1, 2, 5);
        when(pocVersionRepository.findTopByPocIdOrderByMajorDescMinorDescPatchDesc(1L)).thenReturn(Optional.of(existing));

        service.deployNewVersion(1L, "admin-1");

        ArgumentCaptor<PocVersion> versionCaptor = ArgumentCaptor.forClass(PocVersion.class);
        verify(pocVersionRepository).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getVersionLabel()).isEqualTo("1.2.6");
    }

    @Test
    void deployNewVersionRollsOverToTheNextMinorWhenPatchWouldExceedTwenty() {
        Poc poc = pocWithGithubUrl(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        PocVersion existing = versionOf(1, 2, 20);
        when(pocVersionRepository.findTopByPocIdOrderByMajorDescMinorDescPatchDesc(1L)).thenReturn(Optional.of(existing));

        service.deployNewVersion(1L, "admin-1");

        ArgumentCaptor<PocVersion> versionCaptor = ArgumentCaptor.forClass(PocVersion.class);
        verify(pocVersionRepository).save(versionCaptor.capture());
        PocVersion version = versionCaptor.getValue();
        assertThat(version.getMajor()).isEqualTo(1);
        assertThat(version.getMinor()).isEqualTo(3);
        assertThat(version.getPatch()).isEqualTo(1);
        assertThat(version.getVersionLabel()).isEqualTo("1.3.1");
    }

    @Test
    void deployNewVersionThrowsWhenGithubUrlIsBlank() {
        Poc poc = new Poc();
        poc.setId(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));

        assertThatThrownBy(() -> service.deployNewVersion(1L, "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MISSING_GITHUB_URL");

        verify(deploymentTrigger, never()).buildAndDeploy(any());
    }

    @Test
    void deployNewVersionResolvesAndStoresANonDefaultManifestBeforeAllocatingAVersion() {
        Poc poc = pocWithGithubUrl(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        when(pipelineProperties.isSkip()).thenReturn(false);
        GitHubRepoRef repo = new GitHubRepoRef("example-org", "contract-agent");
        when(gitHubService.parseRepoUrl(poc.getGithubUrl())).thenReturn(repo);
        when(gitHubService.getDefaultBranchHeadSha(repo)).thenReturn("abc123");
        String rawYaml = "containers:\n  - name: api\n    role: ingress\n";
        when(manifestService.resolveForBuild(repo, "abc123"))
                .thenReturn(new ManifestResolution(rawYaml, twoContainerManifest()));

        service.deployNewVersion(1L, "admin-1");

        ArgumentCaptor<PocVersion> versionCaptor = ArgumentCaptor.forClass(PocVersion.class);
        verify(pocVersionRepository, org.mockito.Mockito.times(2)).save(versionCaptor.capture());
        assertThat(versionCaptor.getValue().getManifestYaml()).isEqualTo(rawYaml);

        ArgumentCaptor<BuildAndDeployRequest> requestCaptor = ArgumentCaptor.forClass(BuildAndDeployRequest.class);
        verify(deploymentTrigger).buildAndDeploy(requestCaptor.capture());
        assertThat(requestCaptor.getValue().commitSha()).isEqualTo("abc123");
        assertThat(requestCaptor.getValue().manifest().containers()).hasSize(2);
    }

    @Test
    void deployNewVersionRejectsAnInvalidManifestBeforeCreatingAnyRow() {
        Poc poc = pocWithGithubUrl(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        when(pipelineProperties.isSkip()).thenReturn(false);
        GitHubRepoRef repo = new GitHubRepoRef("example-org", "contract-agent");
        when(gitHubService.parseRepoUrl(poc.getGithubUrl())).thenReturn(repo);
        when(gitHubService.getDefaultBranchHeadSha(repo)).thenReturn("abc123");
        when(manifestService.resolveForBuild(repo, "abc123"))
                .thenThrow(new com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestValidationException(
                        List.of("exactly one container must have role 'ingress' — none was found")));

        assertThatThrownBy(() -> service.deployNewVersion(1L, "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MANIFEST_VALIDATION_ERROR");

        verify(pocVersionRepository, never()).save(any());
        verify(pocDeploymentRepository, never()).save(any());
        verify(deploymentTrigger, never()).buildAndDeploy(any());
    }

    private static PocVersion versionOf(int major, int minor, int patch) {
        PocVersion version = new PocVersion();
        version.setId(1L);
        version.setPocId(1L);
        version.setMajor(major);
        version.setMinor(minor);
        version.setPatch(patch);
        version.setVersionLabel(major + "." + minor + "." + patch);
        return version;
    }

    @Test
    void redeployVersionCallsRedeployWithTheExistingImageAndAllocatesNoNewVersion() {
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        PocVersion version = versionOf(1, 0, 1);
        version.setContainerImage("registry/company/contract-agent:1.0.1");
        when(pocVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        PocDeployment deployment = service.redeployVersion(1L, 1L, "admin-1");

        assertThat(deployment.getKind()).isEqualTo("REDEPLOY");
        assertThat(deployment.getPocVersionId()).isEqualTo(1L);
        verify(pocVersionRepository, never()).save(any());

        ArgumentCaptor<RedeployRequest> requestCaptor = ArgumentCaptor.forClass(RedeployRequest.class);
        verify(deploymentTrigger).redeploy(requestCaptor.capture());
        // No PocVersionContainer rows for this version — falls back to the single legacy image
        // under the synthesized default's ingress name, exactly like a pre-manifest version.
        assertThat(requestCaptor.getValue().imagesByContainer()).containsExactly(Map.entry("app", "registry/company/contract-agent:1.0.1"));
        verifyNoInteractions(gitHubService);
    }

    @Test
    void redeployVersionUsesTheVersionsPersistedContainerRowsWhenPresent() {
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        PocVersion version = versionOf(1, 0, 1);
        version.setContainerImage("registry/company/contract-agent/api:1.0.1");
        when(pocVersionRepository.findById(1L)).thenReturn(Optional.of(version));
        PocVersionContainer api = new PocVersionContainer();
        api.setName("api");
        api.setContainerImage("registry/company/contract-agent/api:1.0.1");
        PocVersionContainer worker = new PocVersionContainer();
        worker.setName("worker");
        worker.setContainerImage("registry/company/contract-agent/worker:1.0.1");
        when(pocVersionContainerRepository.findByPocVersionId(1L)).thenReturn(List.of(api, worker));

        service.redeployVersion(1L, 1L, "admin-1");

        ArgumentCaptor<RedeployRequest> requestCaptor = ArgumentCaptor.forClass(RedeployRequest.class);
        verify(deploymentTrigger).redeploy(requestCaptor.capture());
        assertThat(requestCaptor.getValue().imagesByContainer())
                .containsEntry("api", "registry/company/contract-agent/api:1.0.1")
                .containsEntry("worker", "registry/company/contract-agent/worker:1.0.1");
    }

    @Test
    void redeployVersionThrowsWhenTheVersionWasNeverSuccessfullyBuilt() {
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        PocVersion version = versionOf(1, 0, 1);
        when(pocVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.redeployVersion(1L, 1L, "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);

        verify(deploymentTrigger, never()).redeploy(any());
    }

    @Test
    void redeployVersionThrowsWhenTheVersionBelongsToADifferentPoc() {
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        PocVersion versionOfAnotherPoc = versionOf(1, 0, 1);
        versionOfAnotherPoc.setPocId(2L);
        versionOfAnotherPoc.setContainerImage("registry/company/other:1.0.1");
        when(pocVersionRepository.findById(1L)).thenReturn(Optional.of(versionOfAnotherPoc));

        assertThatThrownBy(() -> service.redeployVersion(1L, 1L, "admin-1"))
                .hasMessageContaining("not found");
    }

    @Test
    void reportStatusSetsCompletedAtAndErrorMessageOnFailure() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        PocDeployment updated = service.reportStatus(deployment.getId(), "FAILED", null, null, null, "https://logs", "Build failed.");

        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getErrorMessage()).isEqualTo("Build failed.");
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getLogsUrl()).isEqualTo("https://logs");
    }

    /**
     * Regression test: a deployment that fails mid-build (e.g. Cloud Build's single shared job
     * throws before ever reaching DEPLOYING) still has whatever containerProgress the last
     * successful reportManifestStatus(BUILDING, ...) call wrote — every container frozen at
     * "PENDING"/Building…. Reported via this method (PipelineRunner.fail), not
     * reportManifestStatus, so nothing else would ever clear it — the admin UI would keep showing
     * "Building…" for every container forever, even though the deployment is plainly FAILED.
     */
    @Test
    void reportStatusOnFailureClearsAnyStaleContainerProgressFromAnEarlierBuildingTransition() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        deployment.setStatus("BUILDING");
        deployment.setContainerProgress("[{\"name\":\"frontend\",\"role\":\"INGRESS\",\"state\":\"PENDING\"}]");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        PocDeployment updated = service.reportStatus(deployment.getId(), "FAILED", null, null, null, null, "Build failed.");

        assertThat(updated.getContainerProgress()).isNull();
    }

    @Test
    void reportStatusOnSkippedClearsAnyContainerProgress() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        deployment.setContainerProgress("[{\"name\":\"app\",\"role\":\"INGRESS\",\"state\":\"PENDING\"}]");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        PocDeployment updated = service.reportStatus(deployment.getId(), "SKIPPED", null, null, null, null, null);

        assertThat(updated.getContainerProgress()).isNull();
    }

    @Test
    void reportStatusOnSuccessSetsTheVersionsContainerImageAndThePocsActiveVersion() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(deployment.getPocVersionId());
        when(pocVersionRepository.findById(deployment.getPocVersionId())).thenReturn(Optional.of(version));
        Poc poc = pocWithGithubUrl(deployment.getPocId());
        when(pocRepository.findById(deployment.getPocId())).thenReturn(Optional.of(poc));

        service.reportStatus(deployment.getId(), "SUCCEEDED", "registry/company/contract-agent:1.0.1", "abc123def456", "https://dummy-poc-abc123-uc.a.run.app", null, null);

        assertThat(version.getContainerImage()).isEqualTo("registry/company/contract-agent:1.0.1");
        assertThat(poc.getActiveVersionId()).isEqualTo(version.getId());
        assertThat(poc.getAppUrl()).isEqualTo("https://dummy-poc-abc123-uc.a.run.app");
    }

    @Test
    void reportStatusOnSuccessForARedeployDoesNotRequireAContainerImage() {
        PocDeployment deployment = pendingDeployment("REDEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(deployment.getPocVersionId());
        version.setContainerImage("registry/company/contract-agent:1.0.1");
        when(pocVersionRepository.findById(deployment.getPocVersionId())).thenReturn(Optional.of(version));
        Poc poc = pocWithGithubUrl(deployment.getPocId());
        when(pocRepository.findById(deployment.getPocId())).thenReturn(Optional.of(poc));

        service.reportStatus(deployment.getId(), "SUCCEEDED", null, null, "https://dummy-poc-abc123-uc.a.run.app", null, null);

        assertThat(poc.getActiveVersionId()).isEqualTo(version.getId());
        assertThat(poc.getAppUrl()).isEqualTo("https://dummy-poc-abc123-uc.a.run.app");
        verify(pocVersionRepository, never()).save(any());
    }

    @Test
    void reportStatusThrowsWhenSucceededIsReportedForABuildWithNoContainerImage() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(deployment.getPocVersionId());
        when(pocVersionRepository.findById(deployment.getPocVersionId())).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.reportStatus(deployment.getId(), "SUCCEEDED", null, null,
                "https://dummy-poc-abc123-uc.a.run.app", null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MISSING_CONTAINER_IMAGE");
    }

    @Test
    void reportStatusThrowsWhenSucceededIsReportedWithNoHostedUrl() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        assertThatThrownBy(() -> service.reportStatus(deployment.getId(), "SUCCEEDED",
                "registry/company/contract-agent:1.0.1", "abc123def456", null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MISSING_HOSTED_URL");
    }

    @Test
    void reportStatusOnSkippedMarksTheDeploymentTerminalWithoutTouchingThePoc() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        PocDeployment updated = service.reportStatus(deployment.getId(), "SKIPPED", null, null, null, null, null);

        assertThat(updated.getStatus()).isEqualTo("SKIPPED");
        assertThat(updated.getCompletedAt()).isNotNull();
        verify(pocRepository, never()).save(any());
    }

    @Test
    void reportStatusThrowsWhenTheDeploymentIsAlreadyTerminal() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        deployment.setStatus("SUCCEEDED");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        assertThatThrownBy(() -> service.reportStatus(deployment.getId(), "BUILDING", null, null, null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void reportManifestStatusOnSuccessPersistsOneContainerRowPerBuiltContainer() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(deployment.getPocVersionId());
        when(pocVersionRepository.findById(deployment.getPocVersionId())).thenReturn(Optional.of(version));
        Poc poc = pocWithGithubUrl(deployment.getPocId());
        when(pocRepository.findById(deployment.getPocId())).thenReturn(Optional.of(poc));

        Map<String, String> images = Map.of("api", "registry/company/contract-agent/api:1.0.1",
                "worker", "registry/company/contract-agent/worker:1.0.1");
        service.reportManifestStatus(deployment.getId(), "SUCCEEDED", twoContainerManifest(), images, "abc123", "https://dummy-poc-abc123-uc.a.run.app");

        assertThat(version.getContainerImage()).isEqualTo("registry/company/contract-agent/api:1.0.1");
        verify(pocVersionContainerRepository).deleteByPocVersionId(version.getId());
        ArgumentCaptor<List<PocVersionContainer>> rowsCaptor = ArgumentCaptor.forClass(List.class);
        verify(pocVersionContainerRepository).saveAll(rowsCaptor.capture());
        assertThat(rowsCaptor.getValue()).hasSize(2)
                .anySatisfy(row -> assertThat(row.getName()).isEqualTo("api"))
                .anySatisfy(row -> assertThat(row.getName()).isEqualTo("worker"));
    }

    @Test
    void reportManifestStatusOnBuildingPersistsAPendingContainerProgressSnapshot() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        PocDeployment updated = service.reportManifestStatus(deployment.getId(), "BUILDING", twoContainerManifest(), null, null, null);

        assertThat(updated.getContainerProgress()).contains("\"state\":\"PENDING\"").contains("\"name\":\"api\"").contains("\"name\":\"worker\"");
    }

    private static PocDeployment pendingDeployment(String kind) {
        PocDeployment deployment = new PocDeployment();
        deployment.setId(UUID.randomUUID());
        deployment.setPocId(1L);
        deployment.setPocVersionId(1L);
        deployment.setKind(kind);
        deployment.setStatus("PENDING");
        deployment.setStartedAt(Instant.now());
        return deployment;
    }

    @Test
    void activeVersionLabelsBatchLoadsLabelsKeyedById() {
        PocVersion v1 = versionOf(1, 0, 1);
        v1.setId(10L);
        PocVersion v2 = versionOf(1, 0, 2);
        v2.setId(11L);
        when(pocVersionRepository.findByIdIn(List.of(10L, 11L))).thenReturn(List.of(v1, v2));

        Map<Long, String> labels = service.activeVersionLabels(List.of(10L, 11L));

        assertThat(labels).containsEntry(10L, "1.0.1").containsEntry(11L, "1.0.2");
    }

    @Test
    void activeVersionLabelsSkipsTheQueryWhenGivenNoIds() {
        Map<Long, String> labels = service.activeVersionLabels(List.of());

        assertThat(labels).isEmpty();
        verify(pocVersionRepository, never()).findByIdIn(any());
    }
    @Test
    void deployNewVersionRefusesAPocWithNoSlug() {
        Poc poc = new Poc();
        poc.setId(1L);
        poc.setGithubUrl("https://github.com/example-org/contract-agent");
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));

        assertThatThrownBy(() -> service.deployNewVersion(1L, "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("POC_SLUG_REQUIRED");

        // Rejected before allocating, so a refused attempt never burns a version number.
        verify(pocVersionRepository, never()).save(any());
    }

    @Test
    void deployNewVersionRefusesAPocThatAlreadyHasADeploymentInProgress() {
        Poc poc = pocWithGithubUrl(1L);
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        when(pocDeploymentRepository.existsByPocIdAndStatusIn(1L, List.of("PENDING", "BUILDING", "DEPLOYING")))
                .thenReturn(true);

        assertThatThrownBy(() -> service.deployNewVersion(1L, "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DEPLOYMENT_ALREADY_IN_PROGRESS");

        verify(pocVersionRepository, never()).save(any());
        verify(deploymentTrigger, never()).buildAndDeploy(any());
    }

    @Test
    void redeployVersionRefusesAPocThatAlreadyHasADeploymentInProgress() {
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        when(pocDeploymentRepository.existsByPocIdAndStatusIn(1L, List.of("PENDING", "BUILDING", "DEPLOYING")))
                .thenReturn(true);

        assertThatThrownBy(() -> service.redeployVersion(1L, 1L, "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DEPLOYMENT_ALREADY_IN_PROGRESS");

        verify(deploymentTrigger, never()).redeploy(any());
    }

    @Test
    void retryDeploymentReusesTheSameRowRatherThanCreatingANewOne() {
        PocDeployment failed = pendingDeployment("BUILD_AND_DEPLOY");
        failed.setStatus("FAILED");
        failed.setErrorMessage("Build failed: npm install exited with code 1");
        failed.setCompletedAt(Instant.now());
        when(pocDeploymentRepository.findById(failed.getId())).thenReturn(Optional.of(failed));
        when(pocDeploymentRepository.findTopByPocIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(failed));
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(1L);
        when(pocVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        PocDeployment retry = service.retryDeployment(failed.getId(), "admin-2");

        // Same row: id, kind and version are unchanged — a retry is another attempt at the same
        // build, not a new one, so the admin's history doesn't grow every time Retry is clicked.
        assertThat(retry.getId()).isEqualTo(failed.getId());
        assertThat(retry.getKind()).isEqualTo("BUILD_AND_DEPLOY");
        assertThat(retry.getPocVersionId()).isEqualTo(1L);
        verify(pocVersionRepository, never()).save(any());

        // Exactly one save — the reset of the existing row — never a second, freshly-created one.
        ArgumentCaptor<PocDeployment> savedCaptor = ArgumentCaptor.forClass(PocDeployment.class);
        verify(pocDeploymentRepository).save(savedCaptor.capture());
        assertThat(savedCaptor.getValue().getId()).isEqualTo(failed.getId());

        // The mutable, attempt-scoped fields reset for the new attempt.
        assertThat(retry.getStatus()).isEqualTo("PENDING");
        assertThat(retry.getErrorMessage()).isNull();
        assertThat(retry.getCompletedAt()).isNull();
        assertThat(retry.getInitiatedBy()).isEqualTo("admin-2");

        ArgumentCaptor<BuildAndDeployRequest> requestCaptor = ArgumentCaptor.forClass(BuildAndDeployRequest.class);
        verify(deploymentTrigger).buildAndDeploy(requestCaptor.capture());
        assertThat(requestCaptor.getValue().deploymentId()).isEqualTo(failed.getId());
        assertThat(requestCaptor.getValue().versionLabel()).isEqualTo("1.0.1");
    }

    @Test
    void retryDeploymentOfARedeployCallsRedeployNotBuildAndDeploy() {
        PocDeployment failed = pendingDeployment("REDEPLOY");
        failed.setStatus("FAILED");
        when(pocDeploymentRepository.findById(failed.getId())).thenReturn(Optional.of(failed));
        when(pocDeploymentRepository.findTopByPocIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(failed));
        when(pocRepository.findById(1L)).thenReturn(Optional.of(pocWithGithubUrl(1L)));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(1L);
        version.setContainerImage("registry/company/contract-agent:1.0.1");
        when(pocVersionRepository.findById(1L)).thenReturn(Optional.of(version));

        service.retryDeployment(failed.getId(), "admin-1");

        verify(deploymentTrigger).redeploy(any());
        verify(deploymentTrigger, never()).buildAndDeploy(any());
        verifyNoInteractions(gitHubService);
    }

    @Test
    void retryDeploymentRefusesADeploymentThatIsNotFailed() {
        PocDeployment succeeded = pendingDeployment("BUILD_AND_DEPLOY");
        succeeded.setStatus("SUCCEEDED");
        when(pocDeploymentRepository.findById(succeeded.getId())).thenReturn(Optional.of(succeeded));

        assertThatThrownBy(() -> service.retryDeployment(succeeded.getId(), "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DEPLOYMENT_NOT_RETRYABLE");

        verify(deploymentTrigger, never()).buildAndDeploy(any());
        verify(deploymentTrigger, never()).redeploy(any());
    }

    @Test
    void retryDeploymentRefusesAFailedDeploymentThatANewerDeploymentHasSuperseded() {
        PocDeployment oldFailed = pendingDeployment("BUILD_AND_DEPLOY");
        oldFailed.setStatus("FAILED");
        PocDeployment newerSucceeded = pendingDeployment("BUILD_AND_DEPLOY");
        newerSucceeded.setStatus("SUCCEEDED");
        when(pocDeploymentRepository.findById(oldFailed.getId())).thenReturn(Optional.of(oldFailed));
        // The most recent deployment for this POC is a different row — oldFailed is history now.
        when(pocDeploymentRepository.findTopByPocIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(newerSucceeded));

        assertThatThrownBy(() -> service.retryDeployment(oldFailed.getId(), "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DEPLOYMENT_NOT_RETRYABLE");

        verify(deploymentTrigger, never()).buildAndDeploy(any());
        verify(deploymentTrigger, never()).redeploy(any());
    }

    @Test
    void retryDeploymentRefusesAPocThatAlreadyHasAnotherDeploymentInProgress() {
        PocDeployment failed = pendingDeployment("BUILD_AND_DEPLOY");
        failed.setStatus("FAILED");
        when(pocDeploymentRepository.findById(failed.getId())).thenReturn(Optional.of(failed));
        when(pocDeploymentRepository.findTopByPocIdOrderByStartedAtDesc(1L)).thenReturn(Optional.of(failed));
        when(pocDeploymentRepository.existsByPocIdAndStatusIn(1L, List.of("PENDING", "BUILDING", "DEPLOYING")))
                .thenReturn(true);

        assertThatThrownBy(() -> service.retryDeployment(failed.getId(), "admin-1"))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("DEPLOYMENT_ALREADY_IN_PROGRESS");
    }

}

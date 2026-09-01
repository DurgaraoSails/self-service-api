package com.sails.ai.selfserviceapi.poc.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import com.sails.ai.selfserviceapi.poc.deployment.BuildAndDeployRequest;
import com.sails.ai.selfserviceapi.poc.deployment.DeploymentTrigger;
import com.sails.ai.selfserviceapi.poc.deployment.RedeployRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
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

class PocDeploymentServiceTest {

    private PocRepository pocRepository;
    private PocVersionRepository pocVersionRepository;
    private PocDeploymentRepository pocDeploymentRepository;
    private DeploymentTrigger deploymentTrigger;
    private PocDeploymentService service;

    @BeforeEach
    void setUp() {
        pocRepository = Mockito.mock(PocRepository.class);
        pocVersionRepository = Mockito.mock(PocVersionRepository.class);
        pocDeploymentRepository = Mockito.mock(PocDeploymentRepository.class);
        deploymentTrigger = Mockito.mock(DeploymentTrigger.class);
        service = new PocDeploymentService(pocRepository, pocVersionRepository, pocDeploymentRepository, deploymentTrigger);

        when(pocVersionRepository.save(any(PocVersion.class))).thenAnswer(invocation -> {
            PocVersion version = invocation.getArgument(0);
            if (version.getId() == null) {
                version.setId(100L);
            }
            return version;
        });
        when(pocDeploymentRepository.save(any(PocDeployment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(pocRepository.save(any(Poc.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Poc pocWithGithubUrl(Long id) {
        Poc poc = new Poc();
        poc.setId(id);
        poc.setName("Contract Agent");
        poc.setGithubUrl("https://github.com/example-org/contract-agent");
        return poc;
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
        assertThat(requestCaptor.getValue().containerImage()).isEqualTo("registry/company/contract-agent:1.0.1");
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

        PocDeployment updated = service.reportStatus(deployment.getId(), "FAILED", null, "https://logs", "Build failed.");

        assertThat(updated.getStatus()).isEqualTo("FAILED");
        assertThat(updated.getErrorMessage()).isEqualTo("Build failed.");
        assertThat(updated.getCompletedAt()).isNotNull();
        assertThat(updated.getLogsUrl()).isEqualTo("https://logs");
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

        service.reportStatus(deployment.getId(), "SUCCEEDED", "registry/company/contract-agent:1.0.1", null, null);

        assertThat(version.getContainerImage()).isEqualTo("registry/company/contract-agent:1.0.1");
        assertThat(poc.getActiveVersionId()).isEqualTo(version.getId());
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

        service.reportStatus(deployment.getId(), "SUCCEEDED", null, null, null);

        assertThat(poc.getActiveVersionId()).isEqualTo(version.getId());
        verify(pocVersionRepository, never()).save(any());
    }

    @Test
    void reportStatusThrowsWhenSucceededIsReportedForABuildWithNoContainerImage() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));
        PocVersion version = versionOf(1, 0, 1);
        version.setId(deployment.getPocVersionId());
        when(pocVersionRepository.findById(deployment.getPocVersionId())).thenReturn(Optional.of(version));

        assertThatThrownBy(() -> service.reportStatus(deployment.getId(), "SUCCEEDED", null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getCode())
                .isEqualTo("MISSING_CONTAINER_IMAGE");
    }

    @Test
    void reportStatusThrowsWhenTheDeploymentIsAlreadyTerminal() {
        PocDeployment deployment = pendingDeployment("BUILD_AND_DEPLOY");
        deployment.setStatus("SUCCEEDED");
        when(pocDeploymentRepository.findById(deployment.getId())).thenReturn(Optional.of(deployment));

        assertThatThrownBy(() -> service.reportStatus(deployment.getId(), "BUILDING", null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getStatus())
                .isEqualTo(HttpStatus.CONFLICT);
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
}

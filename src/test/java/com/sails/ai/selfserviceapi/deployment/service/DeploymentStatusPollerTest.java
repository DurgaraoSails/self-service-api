package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildStatus;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class DeploymentStatusPollerTest {

    private PocDeploymentRepository pocDeploymentRepository;
    private PocRepository pocRepository;
    private BuildService buildService;
    private DeploymentStatusPoller poller;

    @BeforeEach
    void setUp() {
        pocDeploymentRepository = Mockito.mock(PocDeploymentRepository.class);
        pocRepository = Mockito.mock(PocRepository.class);
        buildService = Mockito.mock(BuildService.class);
        poller = new DeploymentStatusPoller(pocDeploymentRepository, pocRepository, buildService);

        when(pocDeploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pocRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static PocDeployment buildingDeployment(String cloudBuildId) {
        PocDeployment deployment = new PocDeployment();
        deployment.setId(10L);
        deployment.setPocId(1L);
        deployment.setReleaseTag("1.0.0");
        deployment.setStatus("building");
        deployment.setCloudBuildId(cloudBuildId);
        return deployment;
    }

    private static Poc poc() {
        Poc poc = new Poc();
        poc.setId(1L);
        poc.setSlug("dummy-poc");
        poc.setDeploymentStatus("building");
        return poc;
    }

    @Test
    void promotesToActiveOnSuccessAndSetsTheCurrentReleaseTag() {
        PocDeployment deployment = buildingDeployment("build-1");
        Poc poc = poc();
        when(pocDeploymentRepository.findByStatus("building")).thenReturn(List.of(deployment));
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        when(buildService.getBuildStatus("build-1")).thenReturn(new BuildStatus("SUCCESS", null));

        poller.pollInFlightBuilds();

        assertThat(deployment.getStatus()).isEqualTo("active");
        assertThat(deployment.getFinishedAt()).isNotNull();
        assertThat(poc.getDeploymentStatus()).isEqualTo("active");
        assertThat(poc.getCurrentReleaseTag()).isEqualTo("1.0.0");
        verify(pocDeploymentRepository).save(deployment);
        verify(pocRepository).save(poc);
    }

    @Test
    void marksFailedOnATerminalFailureStatusWithTheReasonFromCloudBuild() {
        PocDeployment deployment = buildingDeployment("build-2");
        Poc poc = poc();
        when(pocDeploymentRepository.findByStatus("building")).thenReturn(List.of(deployment));
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));
        when(buildService.getBuildStatus("build-2")).thenReturn(new BuildStatus("FAILURE", "step 0 failed: permission denied"));

        poller.pollInFlightBuilds();

        assertThat(deployment.getStatus()).isEqualTo("failed");
        assertThat(deployment.getFailureReason()).isEqualTo("step 0 failed: permission denied");
        assertThat(poc.getDeploymentStatus()).isEqualTo("failed");
        assertThat(poc.getCurrentReleaseTag()).isNull();
    }

    @Test
    void leavesNonTerminalBuildsAlone() {
        PocDeployment deployment = buildingDeployment("build-3");
        when(pocDeploymentRepository.findByStatus("building")).thenReturn(List.of(deployment));
        when(buildService.getBuildStatus("build-3")).thenReturn(new BuildStatus("WORKING", null));

        poller.pollInFlightBuilds();

        assertThat(deployment.getStatus()).isEqualTo("building");
        verifyNoInteractions(pocRepository);
        Mockito.verify(pocDeploymentRepository, Mockito.never()).save(any());
    }

    @Test
    void skipsDeploymentsWithNoCloudBuildIdYet() {
        PocDeployment deployment = buildingDeployment(null);
        when(pocDeploymentRepository.findByStatus("building")).thenReturn(List.of(deployment));

        poller.pollInFlightBuilds();

        verifyNoInteractions(buildService);
    }

    @Test
    void toleratesATransientPollingFailureAndLeavesTheDeploymentBuilding() {
        PocDeployment deployment = buildingDeployment("build-4");
        when(pocDeploymentRepository.findByStatus("building")).thenReturn(List.of(deployment));
        when(buildService.getBuildStatus("build-4")).thenThrow(new RuntimeException("network blip"));

        poller.pollInFlightBuilds();

        assertThat(deployment.getStatus()).isEqualTo("building");
        verifyNoInteractions(pocRepository);
    }
}

package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.exception.GitHubApiException;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildRequest;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildSubmission;
import com.sails.ai.selfserviceapi.deployment.service.VersionService.BumpType;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class DeploymentOrchestratorTest {

    private GitHubService gitHubService;
    private VersionService versionService;
    private BuildService buildService;
    private PocRepository pocRepository;
    private PocDeploymentRepository pocDeploymentRepository;
    private DeploymentOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        gitHubService = Mockito.mock(GitHubService.class);
        versionService = Mockito.mock(VersionService.class);
        buildService = Mockito.mock(BuildService.class);
        pocRepository = Mockito.mock(PocRepository.class);
        pocDeploymentRepository = Mockito.mock(PocDeploymentRepository.class);
        orchestrator = new DeploymentOrchestrator(
                gitHubService, versionService, buildService, pocRepository, pocDeploymentRepository);

        // save() just echoes back what it was given, like a real repository would (with the same
        // instance, since we never set an id) — lets us assert on the object we built.
        when(pocRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(pocDeploymentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static Poc pocWithGithubUrl(String url) {
        Poc poc = new Poc();
        poc.setId(1L);
        poc.setSlug("dummy-poc");
        poc.setGithubUrl(url);
        return poc;
    }

    @Test
    void onSuccessRecordsTheDeploymentAndSubmitsTheBuild() {
        Poc poc = pocWithGithubUrl("https://github.com/DurgaraoSails/dummy-poc");
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));

        GitHubRepoRef repo = new GitHubRepoRef("DurgaraoSails", "dummy-poc");
        when(gitHubService.parseRepoUrl(poc.getGithubUrl())).thenReturn(repo);
        when(gitHubService.getDefaultBranch(repo)).thenReturn("main");
        when(gitHubService.getBranchHeadSha(repo, "main")).thenReturn("abc123");
        when(gitHubService.listTagNames(repo)).thenReturn(List.of());
        when(versionService.computeNextVersion(List.of(), BumpType.MINOR)).thenReturn("1.0.0");
        when(buildService.submitBuild(new BuildRequest("dummy-poc", "1.0.0", repo)))
                .thenReturn(new BuildSubmission("build-99", "image-uri:1.0.0"));

        orchestrator.triggerInitialDeployment(1L, "admin-user-id");

        verify(gitHubService).createTag(repo, "1.0.0", "abc123");

        ArgumentCaptor<PocDeployment> deploymentCaptor = ArgumentCaptor.forClass(PocDeployment.class);
        verify(pocDeploymentRepository, Mockito.atLeastOnce()).save(deploymentCaptor.capture());
        PocDeployment finalDeployment = deploymentCaptor.getValue();
        assertThat(finalDeployment.getReleaseTag()).isEqualTo("1.0.0");
        assertThat(finalDeployment.getCloudBuildId()).isEqualTo("build-99");
        assertThat(finalDeployment.getImageUri()).isEqualTo("image-uri:1.0.0");
        assertThat(finalDeployment.getStatus()).isEqualTo("building");
        assertThat(finalDeployment.getTriggeredBy()).isEqualTo("admin-user-id");

        assertThat(poc.getDeploymentStatus()).isEqualTo("building");
        assertThat(poc.getLatestMainCommitSha()).isEqualTo("abc123");
        assertThat(poc.getLatestMainCheckedAt()).isNotNull();
    }

    @Test
    void onFailureMarksBothThePocAndTheDeploymentRowFailed() {
        Poc poc = pocWithGithubUrl("https://github.com/DurgaraoSails/dummy-poc");
        when(pocRepository.findById(1L)).thenReturn(Optional.of(poc));

        GitHubRepoRef repo = new GitHubRepoRef("DurgaraoSails", "dummy-poc");
        when(gitHubService.parseRepoUrl(poc.getGithubUrl())).thenReturn(repo);
        when(gitHubService.getDefaultBranch(repo)).thenThrow(new GitHubApiException("GitHub is down"));

        orchestrator.triggerInitialDeployment(1L, "admin-user-id");

        assertThat(poc.getDeploymentStatus()).isEqualTo("failed");

        ArgumentCaptor<PocDeployment> deploymentCaptor = ArgumentCaptor.forClass(PocDeployment.class);
        verify(pocDeploymentRepository, Mockito.atLeastOnce()).save(deploymentCaptor.capture());
        PocDeployment finalDeployment = deploymentCaptor.getValue();
        assertThat(finalDeployment.getStatus()).isEqualTo("failed");
        assertThat(finalDeployment.getFailureReason()).contains("GitHub is down");
        // Never got far enough to compute a real tag — the placeholder must survive so the
        // NOT NULL release_tag column is always satisfiable, even on an early failure.
        assertThat(finalDeployment.getReleaseTag()).isEqualTo("pending");
        assertThat(finalDeployment.getFinishedAt()).isNotNull();

        verifyNoInteractions(buildService);
    }

    @Test
    void doesNothingIfThePocDisappearedBeforeThePipelineStarted() {
        when(pocRepository.findById(99L)).thenReturn(Optional.empty());

        orchestrator.triggerInitialDeployment(99L, "admin-user-id");

        verifyNoInteractions(gitHubService, buildService, pocDeploymentRepository);
    }
}

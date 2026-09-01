package com.sails.ai.selfserviceapi.deployment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class CheckUpdatesServiceTest {

    private GitHubService gitHubService;
    private PocDeploymentRepository pocDeploymentRepository;
    private PocRepository pocRepository;
    private CheckUpdatesService checkUpdatesService;

    private static final GitHubRepoRef REPO = new GitHubRepoRef("DurgaraoSails", "dummy-poc");

    @BeforeEach
    void setUp() {
        gitHubService = Mockito.mock(GitHubService.class);
        pocDeploymentRepository = Mockito.mock(PocDeploymentRepository.class);
        pocRepository = Mockito.mock(PocRepository.class);
        checkUpdatesService = new CheckUpdatesService(gitHubService, pocDeploymentRepository, pocRepository);

        when(pocRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        when(gitHubService.parseRepoUrl(Mockito.anyString())).thenReturn(REPO);
        when(gitHubService.getDefaultBranch(REPO)).thenReturn("main");
    }

    private static Poc poc() {
        Poc poc = new Poc();
        poc.setId(1L);
        poc.setGithubUrl("https://github.com/DurgaraoSails/dummy-poc");
        return poc;
    }

    private static PocDeployment activeDeploymentFrom(String commitSha) {
        PocDeployment deployment = new PocDeployment();
        deployment.setCommitSha(commitSha);
        return deployment;
    }

    @Test
    void reportsAnUpdateWhenMainHasMovedPastTheActiveDeployment() {
        Poc poc = poc();
        when(gitHubService.getBranchHeadSha(REPO, "main")).thenReturn("new-sha");
        when(pocDeploymentRepository.findFirstByPocIdAndStatusOrderByCreatedAtDesc(1L, "active"))
                .thenReturn(Optional.of(activeDeploymentFrom("old-sha")));

        CheckUpdatesService.CheckUpdatesResult result = checkUpdatesService.checkForUpdates(poc);

        assertThat(result.updateAvailable()).isTrue();
        assertThat(result.latestMainCommitSha()).isEqualTo("new-sha");
        assertThat(result.deployedCommitSha()).isEqualTo("old-sha");
        assertThat(poc.getLatestMainCommitSha()).isEqualTo("new-sha");
        assertThat(poc.getLatestMainCheckedAt()).isNotNull();
    }

    @Test
    void reportsNoUpdateWhenTheActiveDeploymentIsAlreadyAtHead() {
        Poc poc = poc();
        when(gitHubService.getBranchHeadSha(REPO, "main")).thenReturn("same-sha");
        when(pocDeploymentRepository.findFirstByPocIdAndStatusOrderByCreatedAtDesc(1L, "active"))
                .thenReturn(Optional.of(activeDeploymentFrom("same-sha")));

        CheckUpdatesService.CheckUpdatesResult result = checkUpdatesService.checkForUpdates(poc);

        assertThat(result.updateAvailable()).isFalse();
    }

    @Test
    void reportsNoUpdateWhenThereIsNoActiveDeploymentToCompareAgainst() {
        Poc poc = poc();
        when(gitHubService.getBranchHeadSha(REPO, "main")).thenReturn("some-sha");
        when(pocDeploymentRepository.findFirstByPocIdAndStatusOrderByCreatedAtDesc(1L, "active"))
                .thenReturn(Optional.empty());

        CheckUpdatesService.CheckUpdatesResult result = checkUpdatesService.checkForUpdates(poc);

        assertThat(result.updateAvailable()).isFalse();
        assertThat(result.deployedCommitSha()).isNull();
    }
}

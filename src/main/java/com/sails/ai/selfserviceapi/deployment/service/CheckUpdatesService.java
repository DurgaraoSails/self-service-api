package com.sails.ai.selfserviceapi.deployment.service;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import org.springframework.stereotype.Service;

/**
 * Answers "is there an update available" by comparing a freshly-fetched main HEAD against the
 * commit the currently-active deployment was actually built from — not against whatever the
 * last *attempted* deploy used, since a failed attempt shouldn't count as "already up to date".
 */
@Service
public class CheckUpdatesService {

    private static final String ACTIVE = "active";

    private final GitHubService gitHubService;
    private final PocDeploymentRepository pocDeploymentRepository;
    private final PocRepository pocRepository;

    public CheckUpdatesService(
            GitHubService gitHubService,
            PocDeploymentRepository pocDeploymentRepository,
            PocRepository pocRepository) {
        this.gitHubService = gitHubService;
        this.pocDeploymentRepository = pocDeploymentRepository;
        this.pocRepository = pocRepository;
    }

    public CheckUpdatesResult checkForUpdates(Poc poc) {
        GitHubRepoRef repo = gitHubService.parseRepoUrl(poc.getGithubUrl());
        String defaultBranch = gitHubService.getDefaultBranch(repo);
        String freshHeadSha = gitHubService.getBranchHeadSha(repo, defaultBranch);

        poc.setLatestMainCommitSha(freshHeadSha);
        poc.setLatestMainCheckedAt(Instant.now());
        pocRepository.save(poc);

        String deployedCommitSha = pocDeploymentRepository
                .findFirstByPocIdAndStatusOrderByCreatedAtDesc(poc.getId(), ACTIVE)
                .map(PocDeployment::getCommitSha)
                .orElse(null);

        boolean updateAvailable = deployedCommitSha != null && !deployedCommitSha.equals(freshHeadSha);

        return new CheckUpdatesResult(updateAvailable, freshHeadSha, deployedCommitSha);
    }

    public record CheckUpdatesResult(boolean updateAvailable, String latestMainCommitSha, String deployedCommitSha) {
    }
}

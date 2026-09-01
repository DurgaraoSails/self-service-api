package com.sails.ai.selfserviceapi.deployment.service;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildRequest;
import com.sails.ai.selfserviceapi.deployment.service.BuildService.BuildSubmission;
import com.sails.ai.selfserviceapi.deployment.service.VersionService.BumpType;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Ties GitHubService, VersionService, and BuildService together for one release: tag main,
 * compute the next version, submit the build. Runs off the request thread — POST /pocs and
 * deploy-new-version must return immediately, not wait on GitHub/Cloud Build round trips.
 *
 * Only covers submission. Confirming the build actually finished (building -> active/failed)
 * is the scheduled poller's job (T10.7), since Cloud Build runs for minutes after this returns.
 */
@Service
public class DeploymentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DeploymentOrchestrator.class);

    private final GitHubService gitHubService;
    private final VersionService versionService;
    private final BuildService buildService;
    private final PocRepository pocRepository;
    private final PocDeploymentRepository pocDeploymentRepository;

    public DeploymentOrchestrator(
            GitHubService gitHubService,
            VersionService versionService,
            BuildService buildService,
            PocRepository pocRepository,
            PocDeploymentRepository pocDeploymentRepository) {
        this.gitHubService = gitHubService;
        this.versionService = versionService;
        this.buildService = buildService;
        this.pocRepository = pocRepository;
        this.pocDeploymentRepository = pocDeploymentRepository;
    }

    /** First deploy for a newly created POC — always version 1.0.0 since there are no tags yet. */
    @Async
    public void triggerInitialDeployment(Long pocId, String triggeredBy) {
        runPipeline(pocId, BumpType.MINOR, triggeredBy);
    }

    /** "Create New Version" — same pipeline, explicit bump type over whatever tags already exist. */
    @Async
    public void triggerNewVersion(Long pocId, BumpType bumpType, String triggeredBy) {
        runPipeline(pocId, bumpType, triggeredBy);
    }

    private void runPipeline(Long pocId, BumpType bumpType, String triggeredBy) {
        Poc poc = pocRepository.findById(pocId).orElse(null);
        if (poc == null) {
            log.warn("Poc {} was gone before its deployment pipeline could start", pocId);
            return;
        }

        poc.setDeploymentStatus("building");
        pocRepository.save(poc);

        // Persisted with a placeholder tag up front — release_tag is NOT NULL, and we want a
        // row to exist (and be visible to the status UI) even if we fail before computing the
        // real one, e.g. a malformed repo URL.
        PocDeployment deployment = new PocDeployment();
        deployment.setPocId(poc.getId());
        deployment.setReleaseTag("pending");
        deployment.setStatus("building");
        deployment.setTriggeredBy(triggeredBy);
        deployment = pocDeploymentRepository.save(deployment);

        try {
            GitHubRepoRef repo = gitHubService.parseRepoUrl(poc.getGithubUrl());
            String defaultBranch = gitHubService.getDefaultBranch(repo);
            String headSha = gitHubService.getBranchHeadSha(repo, defaultBranch);
            List<String> existingTags = gitHubService.listTagNames(repo);
            String releaseTag = versionService.computeNextVersion(existingTags, bumpType);

            gitHubService.createTag(repo, releaseTag, headSha);

            BuildSubmission submission = buildService.submitBuild(new BuildRequest(poc.getSlug(), releaseTag, repo));

            deployment.setReleaseTag(releaseTag);
            deployment.setCommitSha(headSha);
            deployment.setCloudBuildId(submission.cloudBuildId());
            deployment.setImageUri(submission.imageUri());
            pocDeploymentRepository.save(deployment);

            // Recorded now because it's just "what we observed on GitHub" (feeds check-updates),
            // not a claim the deploy succeeded — currentReleaseTag is the poller's call to make.
            poc.setLatestMainCommitSha(headSha);
            poc.setLatestMainCheckedAt(Instant.now());
            pocRepository.save(poc);

            log.info("Submitted build {} for poc '{}' release {}", submission.cloudBuildId(), poc.getSlug(), releaseTag);
        } catch (Exception e) {
            log.error("Deployment pipeline failed for poc '{}'", poc.getSlug(), e);

            deployment.setStatus("failed");
            deployment.setFailureReason(truncate(e.getMessage()));
            deployment.setFinishedAt(Instant.now());
            pocDeploymentRepository.save(deployment);

            poc.setDeploymentStatus("failed");
            pocRepository.save(poc);
        }
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error — see application logs";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}

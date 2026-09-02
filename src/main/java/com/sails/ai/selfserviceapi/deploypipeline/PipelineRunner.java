package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Ties GitHubService and PipelineExecutor together for one deployment, and reports every
 * transition to {@link PocDeploymentService} as it happens — no webhook, no queue: this runs in
 * the same process, so "the pipeline reports status" is just a direct method call.
 *
 * <p>Both entry points are {@code @Async}: PocDeploymentService has already committed a PENDING
 * deployment row and returned to the caller before either of these runs, which is what makes
 * deploy/redeploy/retry non-blocking.
 */
@Service
public class PipelineRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final GitHubService gitHubService;
    private final PipelineExecutor executor;
    private final PocDeploymentService pocDeploymentService;
    private final PipelineProperties properties;

    public PipelineRunner(GitHubService gitHubService, PipelineExecutor executor,
                           PocDeploymentService pocDeploymentService, PipelineProperties properties) {
        this.gitHubService = gitHubService;
        this.executor = executor;
        this.pocDeploymentService = pocDeploymentService;
        this.properties = properties;
    }

    @Async
    public void runBuildAndDeploy(UUID deploymentId, String pocSlug, String githubUrl, String versionLabel) {
        if (properties.isSkip()) {
            skip(deploymentId, pocSlug, versionLabel);
            return;
        }
        try {
            GitHubRepoRef repo = gitHubService.parseRepoUrl(githubUrl);
            String commitSha = gitHubService.getDefaultBranchHeadSha(repo);

            // Tagging before building, and cloning the tag rather than the branch, is what makes
            // this reproducible — the image can only ever contain the commit the tag points at.
            gitHubService.createTagIfAbsent(repo, versionLabel, commitSha);

            pocDeploymentService.reportStatus(deploymentId, "BUILDING", null, null, null, null, null);
            String image = executor.buildAndPushImage(repo, versionLabel, pocSlug);

            pocDeploymentService.reportStatus(deploymentId, "DEPLOYING", null, null, null, null, null);
            String hostedUrl = executor.deploy(pocSlug, image);

            pocDeploymentService.reportStatus(deploymentId, "SUCCEEDED", image, commitSha, hostedUrl, null, null);
            log.info("Deployment {} succeeded — '{}' version {} is live at {}",
                    deploymentId, pocSlug, versionLabel, hostedUrl);
        } catch (Exception e) {
            fail(deploymentId, pocSlug, versionLabel, e);
        }
    }

    /** Rollback: the image already exists, so there is nothing to clone, tag or build. */
    @Async
    public void runRedeploy(UUID deploymentId, String pocSlug, String containerImage, String versionLabel) {
        if (properties.isSkip()) {
            skip(deploymentId, pocSlug, versionLabel);
            return;
        }
        try {
            pocDeploymentService.reportStatus(deploymentId, "DEPLOYING", null, null, null, null, null);
            String hostedUrl = executor.deploy(pocSlug, containerImage);

            pocDeploymentService.reportStatus(deploymentId, "SUCCEEDED", containerImage, null, hostedUrl, null, null);
            log.info("Deployment {} succeeded — '{}' rolled back to version {} at {}",
                    deploymentId, pocSlug, versionLabel, hostedUrl);
        } catch (Exception e) {
            fail(deploymentId, pocSlug, versionLabel, e);
        }
    }

    /** No GitHub tag, no build, no deploy — pipeline.executor=skip means none of it runs at all. */
    private void skip(UUID deploymentId, String pocSlug, String versionLabel) {
        log.info("Deployment {} skipped — pipeline.executor=skip, poc '{}' version {} was not built or deployed",
                deploymentId, pocSlug, versionLabel);
        try {
            pocDeploymentService.reportStatus(deploymentId, "SKIPPED", null, null, null, null, null);
        } catch (Exception reportFailure) {
            log.error("Could not record deployment {} as SKIPPED — it will look stuck", deploymentId, reportFailure);
        }
    }

    private void fail(UUID deploymentId, String pocSlug, String versionLabel, Exception e) {
        log.error("Deployment {} failed — poc '{}' version {}", deploymentId, pocSlug, versionLabel, e);
        try {
            pocDeploymentService.reportStatus(deploymentId, "FAILED", null, null, null, null, truncate(e.getMessage()));
        } catch (Exception reportFailure) {
            // Nothing else will ever touch this deployment again — if even this fails, the
            // admin is left with a deployment stuck mid-flight and only the log above to explain it.
            log.error("Could not record deployment {} as FAILED — it will look stuck", deploymentId, reportFailure);
        }
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Deployment failed — see application logs";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}

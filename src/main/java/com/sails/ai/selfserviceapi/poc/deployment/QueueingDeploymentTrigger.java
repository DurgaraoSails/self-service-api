package com.sails.ai.selfserviceapi.poc.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Hands work to the deploy pipeline by writing a row it will claim.
 *
 * A queue table rather than an HTTP call, deliberately: the pipeline runs as a scheduled job and
 * is not listening most of the time, and an enqueue that only succeeds when the far side happens
 * to be up would need its own retry and durability story. A committed row already has both.
 *
 * The insert carries everything the pipeline needs — slug, repository, version, image — so it
 * never has to read the POC tables. That keeps the coupling to a single table with a stable
 * shape, rather than to this service's whole domain model.
 */
@Component
@ConditionalOnProperty(prefix = "deployment", name = "trigger", havingValue = "queue", matchIfMissing = true)
public class QueueingDeploymentTrigger implements DeploymentTrigger {

    private static final Logger log = LoggerFactory.getLogger(QueueingDeploymentTrigger.class);

    private final DeploymentJobRepository jobRepository;

    public QueueingDeploymentTrigger(DeploymentJobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    @Override
    public void buildAndDeploy(BuildAndDeployRequest request) {
        enqueue(request.deploymentId(), request.pocSlug(), request.versionLabel(),
                "BUILD_AND_DEPLOY", request.githubUrl(), null);
    }

    @Override
    public void redeploy(RedeployRequest request) {
        enqueue(request.deploymentId(), request.pocSlug(), request.versionLabel(),
                "REDEPLOY", null, request.containerImage());
    }

    private void enqueue(java.util.UUID deploymentId, String pocSlug, String versionLabel,
                          String kind, String githubUrl, String containerImage) {
        DeploymentJob job = new DeploymentJob();
        job.setDeploymentId(deploymentId);
        job.setPocSlug(pocSlug);
        job.setVersionLabel(versionLabel);
        job.setKind(kind);
        job.setGithubUrl(githubUrl);
        job.setContainerImage(containerImage);

        jobRepository.save(job);
        log.info("Queued {} for poc '{}' version {} (deployment {})",
                kind, pocSlug, versionLabel, deploymentId);
    }
}

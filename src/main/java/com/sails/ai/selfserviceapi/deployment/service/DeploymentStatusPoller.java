package com.sails.ai.selfserviceapi.deployment.service;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * DeploymentOrchestrator only submits builds — Cloud Build itself takes minutes to actually
 * finish, well past when that request returns. This is what confirms real outcomes: polls every
 * still-"building" poc_deployments row and flips it (and the owning POC) to active/failed once
 * Cloud Build reaches a terminal state. This is also the only place poc.currentReleaseTag ever
 * gets set — "submitted" is not "live".
 */
@Service
public class DeploymentStatusPoller {

    private static final Logger log = LoggerFactory.getLogger(DeploymentStatusPoller.class);
    private static final String STATUS_BUILDING = "building";

    private final PocDeploymentRepository pocDeploymentRepository;
    private final PocRepository pocRepository;
    private final BuildService buildService;

    public DeploymentStatusPoller(
            PocDeploymentRepository pocDeploymentRepository,
            PocRepository pocRepository,
            BuildService buildService) {
        this.pocDeploymentRepository = pocDeploymentRepository;
        this.pocRepository = pocRepository;
        this.buildService = buildService;
    }

    @Scheduled(fixedDelayString = "${deployment.poll-interval-ms:15000}")
    public void pollInFlightBuilds() {
        List<PocDeployment> inFlight = pocDeploymentRepository.findByStatus(STATUS_BUILDING);
        for (PocDeployment deployment : inFlight) {
            // Null means the orchestrator hasn't reached the submit step yet (or already failed
            // before it, which would have set status="failed", not left it "building") — nothing
            // to poll yet either way.
            if (deployment.getCloudBuildId() != null) {
                pollOne(deployment);
            }
        }
    }

    private void pollOne(PocDeployment deployment) {
        BuildService.BuildStatus buildStatus;
        try {
            buildStatus = buildService.getBuildStatus(deployment.getCloudBuildId());
        } catch (Exception e) {
            // Transient API/network hiccup — leave it "building" and retry on the next tick
            // rather than failing a deployment over a polling error.
            log.warn("Could not fetch Cloud Build status for deployment {} (build {})",
                    deployment.getId(), deployment.getCloudBuildId(), e);
            return;
        }

        if (!buildStatus.isTerminal()) {
            return;
        }

        Poc poc = pocRepository.findById(deployment.getPocId()).orElse(null);
        if (poc == null) {
            log.warn("Poc {} disappeared while deployment {} was in flight", deployment.getPocId(), deployment.getId());
            return;
        }

        deployment.setFinishedAt(Instant.now());

        if (buildStatus.isSuccess()) {
            deployment.setStatus("active");
            poc.setDeploymentStatus("active");
            poc.setCurrentReleaseTag(deployment.getReleaseTag());
            log.info("Deployment {} for poc '{}' is now active at release {}",
                    deployment.getId(), poc.getSlug(), deployment.getReleaseTag());
        } else {
            deployment.setStatus("failed");
            deployment.setFailureReason(truncate(buildStatus.failureDetail()));
            poc.setDeploymentStatus("failed");
            log.warn("Deployment {} for poc '{}' failed: {}", deployment.getId(), poc.getSlug(), buildStatus.failureDetail());
        }

        pocDeploymentRepository.save(deployment);
        pocRepository.save(poc);
    }

    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "Build failed — see Cloud Build logs";
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }
}

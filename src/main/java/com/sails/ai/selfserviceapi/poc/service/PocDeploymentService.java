package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.deployment.BuildAndDeployRequest;
import com.sails.ai.selfserviceapi.poc.deployment.DeploymentTrigger;
import com.sails.ai.selfserviceapi.poc.deployment.RedeployRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.exception.DeploymentAlreadyInProgressException;
import com.sails.ai.selfserviceapi.poc.exception.DeploymentAlreadyTerminalException;
import com.sails.ai.selfserviceapi.poc.exception.DeploymentNotRetryableException;
import com.sails.ai.selfserviceapi.poc.exception.MissingContainerImageException;
import com.sails.ai.selfserviceapi.poc.exception.MissingGithubUrlException;
import com.sails.ai.selfserviceapi.poc.exception.MissingHostedUrlException;
import com.sails.ai.selfserviceapi.poc.exception.MissingPocSlugException;
import com.sails.ai.selfserviceapi.poc.exception.NoBuiltImageException;
import com.sails.ai.selfserviceapi.poc.exception.PocDeploymentNotFoundException;
import com.sails.ai.selfserviceapi.poc.exception.PocNotFoundException;
import com.sails.ai.selfserviceapi.poc.exception.PocVersionNotFoundException;
import com.sails.ai.selfserviceapi.poc.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Versioning and deployment tracking for POCs. Kept separate from {@link PocService} (which owns
 * plain POC metadata CRUD) since this is a distinct concern with its own lifecycle — mirrors why
 * {@code PocFields} was split out on its own once POC CRUD grew.
 */
@Service
public class PocDeploymentService {

    private static final int MAX_PATCH = 20;
    private static final String BUILD_AND_DEPLOY = "BUILD_AND_DEPLOY";
    private static final String REDEPLOY = "REDEPLOY";
    private static final String PENDING = "PENDING";
    private static final String BUILDING = "BUILDING";
    private static final String DEPLOYING = "DEPLOYING";
    private static final String SUCCEEDED = "SUCCEEDED";
    private static final String FAILED = "FAILED";
    private static final String SKIPPED = "SKIPPED";
    private static final List<String> IN_PROGRESS_STATUSES = List.of(PENDING, BUILDING, DEPLOYING);

    private final PocRepository pocRepository;
    private final PocVersionRepository pocVersionRepository;
    private final PocDeploymentRepository pocDeploymentRepository;
    private final DeploymentTrigger deploymentTrigger;

    public PocDeploymentService(PocRepository pocRepository,
                                 PocVersionRepository pocVersionRepository,
                                 PocDeploymentRepository pocDeploymentRepository,
                                 DeploymentTrigger deploymentTrigger) {
        this.pocRepository = pocRepository;
        this.pocVersionRepository = pocVersionRepository;
        this.pocDeploymentRepository = pocDeploymentRepository;
        this.deploymentTrigger = deploymentTrigger;
    }

    /**
     * Allocates the next version number, records a PENDING deployment, and calls the trigger.
     * The trigger call is deliberately the last statement — with today's synchronous, no-op
     * {@code LoggingDeploymentTrigger} this is inert either way, but once a real (networked)
     * trigger is wired in, firing it only after the persist calls above have run reduces the
     * window for the pipeline's callback to race an uncommitted row.
     */
    @Transactional
    public PocDeployment deployNewVersion(Long pocId, String initiatedByUserId) {
        Poc poc = getPoc(pocId);
        if (poc.getGithubUrl() == null || poc.getGithubUrl().isBlank()) {
            throw new MissingGithubUrlException(pocId);
        }
        requireSlug(poc);
        requireNoActiveDeployment(pocId);

        PocVersion version = allocateNextVersion(pocId);
        PocDeployment deployment = createDeployment(pocId, version.getId(), BUILD_AND_DEPLOY, initiatedByUserId);

        deploymentTrigger.buildAndDeploy(new BuildAndDeployRequest(
                deployment.getId(), pocId, poc.getSlug(), poc.getGithubUrl(), version.getVersionLabel()));
        return deployment;
    }

    @Transactional
    public PocDeployment redeployVersion(Long pocId, Long versionId, String initiatedByUserId) {
        Poc poc = getPoc(pocId);
        requireSlug(poc);
        requireNoActiveDeployment(pocId);
        PocVersion version = pocVersionRepository.findById(versionId)
                .orElseThrow(() -> new PocVersionNotFoundException(versionId));
        if (!version.getPocId().equals(pocId)) {
            throw new PocVersionNotFoundException(versionId);
        }
        if (version.getContainerImage() == null || version.getContainerImage().isBlank()) {
            throw new NoBuiltImageException(versionId);
        }

        PocDeployment deployment = createDeployment(pocId, versionId, REDEPLOY, initiatedByUserId);

        deploymentTrigger.redeploy(new RedeployRequest(
                deployment.getId(), pocId, poc.getSlug(), version.getContainerImage(), version.getVersionLabel()));
        return deployment;
    }

    /**
     * The pipeline addresses the deploy target by slug, so a POC without one cannot be deployed
     * at all. Checked before allocating a version, so a rejected attempt doesn't burn a number.
     */
    private void requireSlug(Poc poc) {
        if (poc.getSlug() == null || poc.getSlug().isBlank()) {
            throw new MissingPocSlugException(poc.getId());
        }
    }

    /**
     * A POC may have at most one non-terminal deployment at a time — otherwise two attempts race
     * to write the same POC's active version, and a slower one can silently clobber a faster
     * one's success. Checked before allocating a version, so a rejected attempt never burns one.
     */
    private void requireNoActiveDeployment(Long pocId) {
        if (pocDeploymentRepository.existsByPocIdAndStatusIn(pocId, IN_PROGRESS_STATUSES)) {
            throw new DeploymentAlreadyInProgressException(pocId);
        }
    }

    /**
     * Re-runs a FAILED deployment: same version, same kind, no new version number allocated
     * (unlike deployNewVersion — a retry is a second attempt at the same release, not a new one).
     */
    @Transactional
    public PocDeployment retryDeployment(UUID deploymentId, String initiatedByUserId) {
        PocDeployment original = getDeploymentById(deploymentId);
        if (!FAILED.equals(original.getStatus())) {
            throw new DeploymentNotRetryableException(deploymentId, original.getStatus());
        }
        requireNoActiveDeployment(original.getPocId());

        Poc poc = getPoc(original.getPocId());
        PocVersion version = pocVersionRepository.findById(original.getPocVersionId())
                .orElseThrow(() -> new PocVersionNotFoundException(original.getPocVersionId()));

        PocDeployment retry = createDeployment(poc.getId(), version.getId(), original.getKind(), initiatedByUserId);

        if (BUILD_AND_DEPLOY.equals(original.getKind())) {
            deploymentTrigger.buildAndDeploy(new BuildAndDeployRequest(
                    retry.getId(), poc.getId(), poc.getSlug(), poc.getGithubUrl(), version.getVersionLabel()));
        } else {
            deploymentTrigger.redeploy(new RedeployRequest(
                    retry.getId(), poc.getId(), poc.getSlug(), version.getContainerImage(), version.getVersionLabel()));
        }
        return retry;
    }

    public List<PocVersion> listVersions(Long pocId) {
        return pocVersionRepository.findByPocIdOrderByMajorDescMinorDescPatchDesc(pocId);
    }

    public List<PocDeployment> listDeployments(Long pocId) {
        return pocDeploymentRepository.findByPocIdOrderByStartedAtDesc(pocId);
    }

    public PocDeployment getDeploymentById(UUID id) {
        return pocDeploymentRepository.findById(id)
                .orElseThrow(() -> new PocDeploymentNotFoundException(id));
    }

    public String versionLabel(Long versionId) {
        return pocVersionRepository.findById(versionId)
                .map(PocVersion::getVersionLabel)
                .orElseThrow(() -> new PocVersionNotFoundException(versionId));
    }

    @Transactional
    public PocDeployment reportStatus(UUID deploymentId, String status, String containerImage, String commitSha,
                                       String hostedUrl, String logsUrl, String errorMessage) {
        PocDeployment deployment = getDeploymentById(deploymentId);
        if (isTerminal(deployment.getStatus())) {
            throw new DeploymentAlreadyTerminalException(deploymentId);
        }

        deployment.setStatus(status);
        if (logsUrl != null) {
            deployment.setLogsUrl(logsUrl);
        }

        if (FAILED.equals(status)) {
            deployment.setErrorMessage(errorMessage);
            deployment.setCompletedAt(Instant.now());
        } else if (SKIPPED.equals(status)) {
            deployment.setCompletedAt(Instant.now());
        } else if (SUCCEEDED.equals(status)) {
            if (hostedUrl == null || hostedUrl.isBlank()) {
                throw new MissingHostedUrlException(deploymentId);
            }
            PocVersion version = pocVersionRepository.findById(deployment.getPocVersionId())
                    .orElseThrow(() -> new PocVersionNotFoundException(deployment.getPocVersionId()));
            if (BUILD_AND_DEPLOY.equals(deployment.getKind())) {
                if (containerImage == null || containerImage.isBlank()) {
                    throw new MissingContainerImageException(deploymentId);
                }
                version.setContainerImage(containerImage);
                // Optional: only a pipeline that builds from source knows the commit, and a
                // REDEPLOY rebuilds nothing, so its version already carries the right one.
                if (commitSha != null && !commitSha.isBlank()) {
                    version.setCommitSha(commitSha);
                }
                pocVersionRepository.save(version);
            }
            // Set together, in this one transaction: a POC's "active" version and its appUrl must
            // never disagree — activeVersionId with a stale/absent appUrl would mean "active, but
            // nowhere to reach it".
            Poc poc = getPoc(deployment.getPocId());
            poc.setActiveVersionId(version.getId());
            poc.setAppUrl(hostedUrl);
            pocRepository.save(poc);
            deployment.setCompletedAt(Instant.now());
        }

        return pocDeploymentRepository.save(deployment);
    }

    /** Batch lookup for GET /pocs — versionIds come from each POC's activeVersionId. */
    public Map<Long, String> activeVersionLabels(List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        return pocVersionRepository.findByIdIn(versionIds).stream()
                .collect(Collectors.toMap(PocVersion::getId, PocVersion::getVersionLabel));
    }

    /** Batch lookup for GET /pocs — one query for the whole list's latestDeploymentStatus. */
    public Map<Long, String> latestDeploymentStatuses(List<Long> pocIds) {
        if (pocIds.isEmpty()) {
            return Map.of();
        }
        return pocDeploymentRepository.findLatestPerPoc(pocIds).stream()
                .collect(Collectors.toMap(PocDeployment::getPocId, PocDeployment::getStatus));
    }

    private Poc getPoc(Long pocId) {
        return pocRepository.findById(pocId).orElseThrow(() -> new PocNotFoundException(pocId));
    }

    private PocVersion allocateNextVersion(Long pocId) {
        PocVersion version = new PocVersion();
        version.setPocId(pocId);

        pocVersionRepository.findTopByPocIdOrderByMajorDescMinorDescPatchDesc(pocId)
                .ifPresentOrElse(
                        existing -> {
                            version.setMajor(existing.getMajor());
                            if (existing.getPatch() < MAX_PATCH) {
                                version.setMinor(existing.getMinor());
                                version.setPatch(existing.getPatch() + 1);
                            } else {
                                version.setMinor(existing.getMinor() + 1);
                                version.setPatch(1);
                            }
                        },
                        () -> {
                            version.setMajor(1);
                            version.setMinor(0);
                            version.setPatch(1);
                        });

        version.setVersionLabel(version.getMajor() + "." + version.getMinor() + "." + version.getPatch());
        return pocVersionRepository.save(version);
    }

    private PocDeployment createDeployment(Long pocId, Long versionId, String kind, String initiatedBy) {
        PocDeployment deployment = new PocDeployment();
        deployment.setPocId(pocId);
        deployment.setPocVersionId(versionId);
        deployment.setKind(kind);
        deployment.setInitiatedBy(initiatedBy);
        return pocDeploymentRepository.save(deployment);
    }

    private boolean isTerminal(String status) {
        return SUCCEEDED.equals(status) || FAILED.equals(status) || SKIPPED.equals(status);
    }
}

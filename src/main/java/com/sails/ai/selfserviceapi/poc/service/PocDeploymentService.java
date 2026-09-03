package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.poc.deployment.BuildAndDeployRequest;
import com.sails.ai.selfserviceapi.poc.deployment.DeploymentTrigger;
import com.sails.ai.selfserviceapi.poc.deployment.RedeployRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
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
import com.sails.ai.selfserviceapi.poc.repository.PocVersionContainerRepository;
import com.sails.ai.selfserviceapi.poc.repository.PocVersionRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
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

    /** The ingress container's name in the manifest synthesized for a repo with no poc.yaml. */
    private static final String DEFAULT_CONTAINER_NAME = "app";

    private final PocRepository pocRepository;
    private final PocVersionRepository pocVersionRepository;
    private final PocVersionContainerRepository pocVersionContainerRepository;
    private final PocDeploymentRepository pocDeploymentRepository;
    private final DeploymentTrigger deploymentTrigger;

    // @Lazy breaks a real cycle: the default DeploymentTrigger (InProcessDeploymentTrigger) wraps
    // PipelineRunner, which itself depends on this service to report status back. Spring can't
    // eagerly construct all three; a lazy proxy here defers resolving the real bean until the
    // first actual trigger call, by which point construction has finished.
    public PocDeploymentService(PocRepository pocRepository,
                                 PocVersionRepository pocVersionRepository,
                                 PocVersionContainerRepository pocVersionContainerRepository,
                                 PocDeploymentRepository pocDeploymentRepository,
                                 @Lazy DeploymentTrigger deploymentTrigger) {
        this.pocRepository = pocRepository;
        this.pocVersionRepository = pocVersionRepository;
        this.pocVersionContainerRepository = pocVersionContainerRepository;
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
        Map<String, String> imagesByContainer = resolveImagesByContainer(version);

        PocDeployment deployment = createDeployment(pocId, versionId, REDEPLOY, initiatedByUserId);

        deploymentTrigger.redeploy(new RedeployRequest(
                deployment.getId(), pocId, poc.getSlug(), imagesByContainer, version.getManifestYaml(), version.getVersionLabel()));
        return deployment;
    }

    /**
     * Every container's already-built image for a version, keyed by name. A pre-manifest version
     * (no {@code poc_version_containers} rows — everything built before this feature existed)
     * falls back to its single {@code containerImage}, under the same name
     * ({@code DEFAULT_CONTAINER_NAME}) the synthesized single-container manifest uses for its
     * ingress container — that's what makes an old version redeploy correctly through the new,
     * manifest-aware pipeline with no data migration.
     */
    private Map<String, String> resolveImagesByContainer(PocVersion version) {
        List<PocVersionContainer> containers = pocVersionContainerRepository.findByPocVersionId(version.getId());
        if (!containers.isEmpty()) {
            return containers.stream()
                    .collect(Collectors.toMap(PocVersionContainer::getName, PocVersionContainer::getContainerImage,
                            (a, b) -> a, LinkedHashMap::new));
        }
        if (version.getContainerImage() == null || version.getContainerImage().isBlank()) {
            throw new NoBuiltImageException(version.getId());
        }
        return Map.of(DEFAULT_CONTAINER_NAME, version.getContainerImage());
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
                    retry.getId(), poc.getId(), poc.getSlug(), resolveImagesByContainer(version),
                    version.getManifestYaml(), version.getVersionLabel()));
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

    /**
     * {@code buildOutcome} carries the per-container detail a BUILD_AND_DEPLOY reports alongside
     * SUCCEEDED — null for every other transition, and null even for a REDEPLOY's SUCCEEDED,
     * since a redeploy rebuilds nothing and its version already carries this from the original
     * build.
     */
    @Transactional
    public PocDeployment reportStatus(UUID deploymentId, String status, String containerImage, String commitSha,
                                       String hostedUrl, String logsUrl, String errorMessage, PocBuildOutcome buildOutcome) {
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
                if (buildOutcome != null) {
                    version.setManifestYaml(buildOutcome.manifestYaml());
                    recordVersionContainers(version.getId(), buildOutcome.containers());
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

    private void recordVersionContainers(Long versionId, List<PocContainerReport> containers) {
        if (containers == null) {
            return;
        }
        for (PocContainerReport report : containers) {
            PocVersionContainer row = new PocVersionContainer();
            row.setPocVersionId(versionId);
            row.setName(report.name());
            row.setRole(report.role());
            row.setContainerImage(report.containerImage());
            row.setPort(report.port());
            pocVersionContainerRepository.save(row);
        }
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

package com.sails.ai.selfserviceapi.poc.service;

import com.sails.ai.selfserviceapi.deploypipeline.config.PipelineProperties;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestResolution;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestService;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Versioning and deployment tracking for POCs. Kept separate from {@link PocService} (which owns
 * plain POC metadata CRUD) since this is a distinct concern with its own lifecycle — mirrors why
 * {@code PocFields} was split out on its own once POC CRUD grew.
 */
@Service
public class PocDeploymentService {

    /** Every container this phase builds comes from the primary repo — matches ManifestService's default. */
    private static final String DEFAULT_CONTAINER_NAME = "app";

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
    private final PocVersionContainerRepository pocVersionContainerRepository;
    private final DeploymentTrigger deploymentTrigger;
    private final PipelineProperties pipelineProperties;
    private final GitHubService gitHubService;
    private final ManifestService manifestService;
    private final ObjectMapper objectMapper;

    // @Lazy breaks a real cycle: the default DeploymentTrigger (InProcessDeploymentTrigger) wraps
    // PipelineRunner, which itself depends on this service to report status back. Spring can't
    // eagerly construct all three; a lazy proxy here defers resolving the real bean until the
    // first actual trigger call, by which point construction has finished.
    public PocDeploymentService(PocRepository pocRepository,
                                 PocVersionRepository pocVersionRepository,
                                 PocDeploymentRepository pocDeploymentRepository,
                                 PocVersionContainerRepository pocVersionContainerRepository,
                                 @Lazy DeploymentTrigger deploymentTrigger,
                                 PipelineProperties pipelineProperties,
                                 GitHubService gitHubService,
                                 ManifestService manifestService,
                                 ObjectMapper objectMapper) {
        this.pocRepository = pocRepository;
        this.pocVersionRepository = pocVersionRepository;
        this.pocDeploymentRepository = pocDeploymentRepository;
        this.pocVersionContainerRepository = pocVersionContainerRepository;
        this.deploymentTrigger = deploymentTrigger;
        this.pipelineProperties = pipelineProperties;
        this.gitHubService = gitHubService;
        this.manifestService = manifestService;
        this.objectMapper = objectMapper;
    }

    /**
     * Allocates the next version number, records a PENDING deployment, and calls the trigger.
     *
     * <p>The POC's manifest is resolved and validated here, synchronously, before either the
     * version or the deployment row is created — a POC with an invalid poc.yaml never gets a
     * PENDING deployment an admin would otherwise see and wonder about. Skipped entirely when
     * {@code pipeline.executor=skip}, which never touches GitHub at all, consistent with its
     * existing contract.
     *
     * <p>The trigger call is deliberately the last statement — firing it only after every persist
     * above has run reduces the window for the pipeline's own callback to race an uncommitted row.
     */
    @Transactional
    public PocDeployment deployNewVersion(Long pocId, String initiatedByUserId) {
        Poc poc = getPoc(pocId);
        if (poc.getGithubUrl() == null || poc.getGithubUrl().isBlank()) {
            throw new MissingGithubUrlException(pocId);
        }
        requireSlug(poc);
        requireNoActiveDeployment(pocId);

        String commitSha = null;
        PocManifest manifest = null;
        String manifestYaml = null;
        if (!pipelineProperties.isSkip()) {
            GitHubRepoRef repo = gitHubService.parseRepoUrl(poc.getGithubUrl());
            commitSha = gitHubService.getDefaultBranchHeadSha(repo);
            ManifestResolution resolution = manifestService.resolveForBuild(repo, commitSha);
            manifest = resolution.manifest();
            manifestYaml = resolution.rawYaml();
        }

        PocVersion version = allocateNextVersion(pocId);
        if (manifestYaml != null) {
            version.setManifestYaml(manifestYaml);
            pocVersionRepository.save(version);
        }
        PocDeployment deployment = createDeployment(pocId, version.getId(), BUILD_AND_DEPLOY, initiatedByUserId);

        deploymentTrigger.buildAndDeploy(new BuildAndDeployRequest(
                deployment.getId(), pocId, poc.getSlug(), poc.getGithubUrl(), version.getVersionLabel(), commitSha, manifest));
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

        // Never a fresh GitHub read — poc.yaml may have changed since this version was built, and
        // a rollback must reproduce exactly what was deployed then.
        PocManifest manifest = manifestService.resolveStored(version.getManifestYaml());
        Map<String, String> imagesByContainer = resolveImagesByContainer(versionId, version.getContainerImage());

        PocDeployment deployment = createDeployment(pocId, versionId, REDEPLOY, initiatedByUserId);

        deploymentTrigger.redeploy(new RedeployRequest(
                deployment.getId(), pocId, poc.getSlug(), version.getVersionLabel(), manifest, imagesByContainer));
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
     * A BUILD_AND_DEPLOY retry re-resolves the manifest (the repo may have fixed a bad poc.yaml
     * since the original attempt failed); a REDEPLOY retry, like any redeploy, never touches
     * GitHub.
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

        if (BUILD_AND_DEPLOY.equals(original.getKind())) {
            String commitSha = null;
            PocManifest manifest = null;
            if (!pipelineProperties.isSkip()) {
                GitHubRepoRef repo = gitHubService.parseRepoUrl(poc.getGithubUrl());
                commitSha = gitHubService.getDefaultBranchHeadSha(repo);
                ManifestResolution resolution = manifestService.resolveForBuild(repo, commitSha);
                manifest = resolution.manifest();
                if (resolution.rawYaml() != null) {
                    version.setManifestYaml(resolution.rawYaml());
                    pocVersionRepository.save(version);
                }
            }
            PocDeployment retry = createDeployment(poc.getId(), version.getId(), original.getKind(), initiatedByUserId);
            deploymentTrigger.buildAndDeploy(new BuildAndDeployRequest(
                    retry.getId(), poc.getId(), poc.getSlug(), poc.getGithubUrl(), version.getVersionLabel(), commitSha, manifest));
            return retry;
        }

        PocManifest manifest = manifestService.resolveStored(version.getManifestYaml());
        Map<String, String> imagesByContainer = resolveImagesByContainer(version.getId(), version.getContainerImage());
        PocDeployment retry = createDeployment(poc.getId(), version.getId(), original.getKind(), initiatedByUserId);
        deploymentTrigger.redeploy(new RedeployRequest(
                retry.getId(), poc.getId(), poc.getSlug(), version.getVersionLabel(), manifest, imagesByContainer));
        return retry;
    }

    /**
     * Resolves the manifest a POC's next build would use, and how many/which containers it
     * declares, without creating a deployment — lets the admin UI show what a deploy would do (or
     * why it would fail) before the admin actually triggers it.
     */
    public PocManifest previewManifest(Long pocId) {
        Poc poc = getPoc(pocId);
        if (poc.getGithubUrl() == null || poc.getGithubUrl().isBlank()) {
            throw new MissingGithubUrlException(pocId);
        }
        GitHubRepoRef repo = gitHubService.parseRepoUrl(poc.getGithubUrl());
        String commitSha = gitHubService.getDefaultBranchHeadSha(repo);
        return manifestService.resolveForBuild(repo, commitSha).manifest();
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

    /** Batch lookup for GET /pocs/{id}/versions — one query for every version's container list. */
    public Map<Long, List<PocVersionContainer>> containersByVersionId(List<Long> versionIds) {
        if (versionIds.isEmpty()) {
            return Map.of();
        }
        return pocVersionContainerRepository.findByPocVersionIdIn(versionIds).stream()
                .collect(Collectors.groupingBy(PocVersionContainer::getPocVersionId));
    }

    /**
     * The webhook-facing status contract (POST /pocs/deployments/{id}/status) — a single
     * containerImage for the whole deployment, no manifest/container breakdown. Used by that
     * endpoint and by PipelineRunner for FAILED/SKIPPED, neither of which touches container state.
     */
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

    /**
     * The in-process pipeline's status contract for BUILDING/DEPLOYING/SUCCEEDED — carries the
     * manifest and, once known, each container's pushed image, so the admin can see a
     * per-container breakdown instead of one coarse status for the whole multi-container deploy.
     * FAILED/SKIPPED go through {@link #reportStatus} instead — neither needs any of this.
     */
    @Transactional
    public PocDeployment reportManifestStatus(UUID deploymentId, String status, PocManifest manifest,
                                               Map<String, String> imagesByContainer, String commitSha, String hostedUrl) {
        PocDeployment deployment = getDeploymentById(deploymentId);
        if (isTerminal(deployment.getStatus())) {
            throw new DeploymentAlreadyTerminalException(deploymentId);
        }

        deployment.setStatus(status);
        deployment.setContainerProgress(containerProgressJson(manifest, imagesByContainer, status));

        if (SUCCEEDED.equals(status)) {
            if (hostedUrl == null || hostedUrl.isBlank()) {
                throw new MissingHostedUrlException(deploymentId);
            }
            PocVersion version = pocVersionRepository.findById(deployment.getPocVersionId())
                    .orElseThrow(() -> new PocVersionNotFoundException(deployment.getPocVersionId()));
            if (BUILD_AND_DEPLOY.equals(deployment.getKind())) {
                ManifestContainer ingress = manifest.ingress();
                String ingressImage = imagesByContainer == null ? null : imagesByContainer.get(ingress.name());
                if (ingressImage == null || ingressImage.isBlank()) {
                    throw new MissingContainerImageException(deploymentId);
                }
                version.setContainerImage(ingressImage);
                if (commitSha != null && !commitSha.isBlank()) {
                    version.setCommitSha(commitSha);
                }
                pocVersionRepository.save(version);
                persistVersionContainers(version.getId(), manifest, imagesByContainer);
            }
            Poc poc = getPoc(deployment.getPocId());
            poc.setActiveVersionId(version.getId());
            poc.setAppUrl(hostedUrl);
            pocRepository.save(poc);
            deployment.setCompletedAt(Instant.now());
        }

        return pocDeploymentRepository.save(deployment);
    }

    /**
     * A version's durable per-container record, keyed by name — falls back to the single legacy
     * containerImage under the synthesized default's ingress name ("app") when a version has no
     * rows here, which is what lets a pre-manifest version redeploy correctly through this
     * manifest-aware pipeline with zero data migration.
     */
    public Map<String, String> resolveImagesByContainer(Long versionId, String legacyContainerImage) {
        List<PocVersionContainer> rows = pocVersionContainerRepository.findByPocVersionId(versionId);
        if (rows.isEmpty()) {
            return Map.of(DEFAULT_CONTAINER_NAME, legacyContainerImage);
        }
        return rows.stream().collect(Collectors.toMap(PocVersionContainer::getName, PocVersionContainer::getContainerImage));
    }

    private void persistVersionContainers(Long versionId, PocManifest manifest, Map<String, String> imagesByContainer) {
        // Delete-then-insert rather than update-in-place: idempotent if a retried build persists
        // the same version's containers twice, and simpler than diffing an admin's manifest edit
        // between two attempts against whatever rows already exist.
        pocVersionContainerRepository.deleteByPocVersionId(versionId);
        List<PocVersionContainer> rows = manifest.containers().stream()
                .map(container -> {
                    PocVersionContainer row = new PocVersionContainer();
                    row.setPocVersionId(versionId);
                    row.setName(container.name());
                    row.setRole(container.role().name());
                    row.setContainerImage(imagesByContainer.get(container.name()));
                    row.setPort(container.port());
                    return row;
                })
                .toList();
        pocVersionContainerRepository.saveAll(rows);
    }

    /**
     * A static snapshot, not live per-container progress: a single Cloud Build job reports one
     * terminal status for the whole multi-step build and one for the whole multi-container deploy,
     * so every container's state flips together at each transition — see {@link ContainerProgress}.
     */
    private String containerProgressJson(PocManifest manifest, Map<String, String> imagesByContainer, String status) {
        if (manifest == null) {
            return null;
        }
        String state = switch (status) {
            case BUILDING -> "PENDING";
            case DEPLOYING -> "BUILT";
            case SUCCEEDED -> "DEPLOYED";
            default -> "PENDING";
        };
        List<ContainerProgress> entries = manifest.containers().stream()
                .map(container -> new ContainerProgress(container.name(), container.role().name(), state,
                        imagesByContainer == null ? null : imagesByContainer.get(container.name()), container.port()))
                .toList();
        return objectMapper.writeValueAsString(entries);
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

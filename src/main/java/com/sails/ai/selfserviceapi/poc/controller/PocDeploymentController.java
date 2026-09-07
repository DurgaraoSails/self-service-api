package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.ManifestContainer;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.generated.api.DeploymentApi;
import com.sails.ai.selfserviceapi.generated.model.ManifestContainerRole;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocManifestPreviewContainer;
import com.sails.ai.selfserviceapi.generated.model.PocManifestPreviewResponse;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSourceRepositoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocVersionResponse;
import com.sails.ai.selfserviceapi.generated.model.ReportDeploymentStatusRequest;
import com.sails.ai.selfserviceapi.generated.model.ReportUpstreamCommitsRequest;
import com.sails.ai.selfserviceapi.generated.model.UpstreamCommit;
import com.sails.ai.selfserviceapi.poc.config.DeploymentWebhookProperties;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.entity.PocVersion;
import com.sails.ai.selfserviceapi.poc.entity.PocVersionContainer;
import com.sails.ai.selfserviceapi.poc.exception.InvalidWebhookSecretException;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocDeploymentController implements DeploymentApi {

    private final PocDeploymentService pocDeploymentService;
    private final PocService pocService;
    private final DeploymentWebhookProperties webhookProperties;
    private final PocDeploymentResponseMapper mapper;

    public PocDeploymentController(PocDeploymentService pocDeploymentService, PocService pocService,
                                    DeploymentWebhookProperties webhookProperties, PocDeploymentResponseMapper mapper) {
        this.pocDeploymentService = pocDeploymentService;
        this.pocService = pocService;
        this.webhookProperties = webhookProperties;
        this.mapper = mapper;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> deployNewVersion(Long id) {
        PocDeployment deployment = pocDeploymentService.deployNewVersion(id, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocVersionResponse>> getPocVersions(Long id) {
        Poc poc = pocService.getById(id);
        List<PocVersion> versions = pocDeploymentService.listVersions(id);
        Map<Long, List<PocVersionContainer>> containersByVersionId = pocDeploymentService.containersByVersionId(
                versions.stream().map(PocVersion::getId).toList());

        List<PocVersionResponse> responses = versions.stream()
                .map(version -> mapper.toVersionResponse(version, version.getId().equals(poc.getActiveVersionId()),
                        containersByVersionId.get(version.getId())))
                .toList();
        return ResponseEntity.ok(responses);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> redeployPocVersion(Long id, Long versionId) {
        PocDeployment deployment = pocDeploymentService.redeployVersion(id, versionId, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocDeploymentResponse>> getPocDeployments(Long id) {
        List<PocDeploymentResponse> deployments = pocDeploymentService.listDeployments(id).stream()
                .map(deployment -> mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)))
                .toList();
        return ResponseEntity.ok(deployments);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> getPocDeploymentById(UUID deploymentId) {
        PocDeployment deployment = pocDeploymentService.getDeploymentById(deploymentId);
        return ResponseEntity.ok(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> retryDeployment(UUID deploymentId) {
        PocDeployment retry = pocDeploymentService.retryDeployment(deploymentId, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(mapper.toDeploymentResponse(retry, versionLabelOf(retry)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocManifestPreviewResponse> getManifestPreview(Long id) {
        PocManifest manifest = pocDeploymentService.previewManifest(id);
        List<PocManifestPreviewContainer> containers = manifest.containers().stream()
                .map(this::toPreviewContainer)
                .toList();
        return ResponseEntity.ok(new PocManifestPreviewResponse(containers));
    }

    private PocManifestPreviewContainer toPreviewContainer(ManifestContainer container) {
        return new PocManifestPreviewContainer(container.name(), ManifestContainerRole.fromValue(container.role().name()))
                .port(container.port());
    }

    @Override
    public ResponseEntity<PocDeploymentResponse> reportDeploymentStatus(UUID deploymentId, String xPipelineWebhookSecret,
                                                                          ReportDeploymentStatusRequest reportDeploymentStatusRequest) {
        if (!MessageDigest.isEqual(
                xPipelineWebhookSecret.getBytes(StandardCharsets.UTF_8),
                webhookProperties.webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSecretException();
        }

        PocDeployment deployment = pocDeploymentService.reportStatus(
                deploymentId,
                reportDeploymentStatusRequest.getStatus().getValue(),
                reportDeploymentStatusRequest.getContainerImage(),
                reportDeploymentStatusRequest.getCommitSha(),
                reportDeploymentStatusRequest.getHostedUrl(),
                reportDeploymentStatusRequest.getLogsUrl(),
                reportDeploymentStatusRequest.getErrorMessage()
        );
        return ResponseEntity.ok(mapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocResponse>> getPocsWithDeploymentIssues() {
        List<Poc> deployable = pocService.listDeployable();
        Map<Long, String> latestStatuses = pocDeploymentService.latestDeploymentStatuses(
                deployable.stream().map(Poc::getId).toList());

        List<Poc> withIssues = deployable.stream()
                .filter(poc -> needsAttention(latestStatuses.get(poc.getId())))
                .toList();

        Map<Long, String> activeVersionLabels = pocDeploymentService.activeVersionLabels(
                withIssues.stream().map(Poc::getActiveVersionId).filter(Objects::nonNull).toList());

        List<PocResponse> responses = withIssues.stream()
                .map(poc -> {
                    Long activeVersionId = poc.getActiveVersionId();
                    String activeVersionLabel = activeVersionId != null ? activeVersionLabels.get(activeVersionId) : null;
                    return PocResponseMapper.toResponse(poc, activeVersionLabel, latestStatuses.get(poc.getId()));
                })
                .toList();
        return ResponseEntity.ok(responses);
    }

    /** Never deployed, or the last attempt didn't succeed. Not BUILDING/DEPLOYING — those are still in flight. */
    private static final Set<String> ISSUE_STATUSES = Set.of("FAILED", "SKIPPED");

    private boolean needsAttention(String latestStatus) {
        return latestStatus == null || ISSUE_STATUSES.contains(latestStatus);
    }

    @Override
    public ResponseEntity<List<PocSourceRepositoryResponse>> getSourceRepositories(String xPipelineWebhookSecret) {
        requirePipelineSecret(xPipelineWebhookSecret);

        List<PocSourceRepositoryResponse> repositories = pocService.listSourceRepositories().stream()
                .map(poc -> new PocSourceRepositoryResponse(poc.getId(), poc.getGithubUrl()).slug(poc.getSlug()))
                .toList();
        return ResponseEntity.ok(repositories);
    }

    @Override
    public ResponseEntity<Void> reportUpstreamCommits(String xPipelineWebhookSecret,
                                                        ReportUpstreamCommitsRequest reportUpstreamCommitsRequest) {
        requirePipelineSecret(xPipelineWebhookSecret);

        Map<Long, String> commitsByPocId = reportUpstreamCommitsRequest.getCommits().stream()
                .collect(Collectors.toMap(UpstreamCommit::getPocId, UpstreamCommit::getCommitSha, (first, second) -> second));
        pocService.recordUpstreamCommits(commitsByPocId);
        return ResponseEntity.noContent().build();
    }

    /** Constant-time comparison — a timing-based probe must not be able to recover the secret. */
    private void requirePipelineSecret(String provided) {
        if (provided == null || !MessageDigest.isEqual(
                provided.getBytes(StandardCharsets.UTF_8),
                webhookProperties.webhookSecret().getBytes(StandardCharsets.UTF_8))) {
            throw new InvalidWebhookSecretException();
        }
    }

    private String versionLabelOf(PocDeployment deployment) {
        return pocDeploymentService.versionLabel(deployment.getPocVersionId());
    }
}

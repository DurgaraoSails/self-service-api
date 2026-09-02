package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.generated.api.DeploymentApi;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSourceRepositoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocVersionResponse;
import com.sails.ai.selfserviceapi.generated.model.ReportDeploymentStatusRequest;
import com.sails.ai.selfserviceapi.generated.model.ReportUpstreamCommitsRequest;
import com.sails.ai.selfserviceapi.generated.model.UpstreamCommit;
import com.sails.ai.selfserviceapi.poc.config.DeploymentWebhookProperties;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.entity.PocDeployment;
import com.sails.ai.selfserviceapi.poc.exception.InvalidWebhookSecretException;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
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

    public PocDeploymentController(PocDeploymentService pocDeploymentService, PocService pocService,
                                    DeploymentWebhookProperties webhookProperties) {
        this.pocDeploymentService = pocDeploymentService;
        this.pocService = pocService;
        this.webhookProperties = webhookProperties;
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> deployNewVersion(Long id) {
        PocDeployment deployment = pocDeploymentService.deployNewVersion(id, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PocDeploymentResponseMapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocVersionResponse>> getPocVersions(Long id) {
        Poc poc = pocService.getById(id);
        List<PocVersionResponse> versions = pocDeploymentService.listVersions(id).stream()
                .map(version -> PocDeploymentResponseMapper.toVersionResponse(version, version.getId().equals(poc.getActiveVersionId())))
                .toList();
        return ResponseEntity.ok(versions);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> redeployPocVersion(Long id, Long versionId) {
        PocDeployment deployment = pocDeploymentService.redeployVersion(id, versionId, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PocDeploymentResponseMapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<PocDeploymentResponse>> getPocDeployments(Long id) {
        List<PocDeploymentResponse> deployments = pocDeploymentService.listDeployments(id).stream()
                .map(deployment -> PocDeploymentResponseMapper.toDeploymentResponse(deployment, versionLabelOf(deployment)))
                .toList();
        return ResponseEntity.ok(deployments);
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> getPocDeploymentById(UUID deploymentId) {
        PocDeployment deployment = pocDeploymentService.getDeploymentById(deploymentId);
        return ResponseEntity.ok(PocDeploymentResponseMapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> retryDeployment(UUID deploymentId) {
        PocDeployment retry = pocDeploymentService.retryDeployment(deploymentId, CurrentUser.id());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(PocDeploymentResponseMapper.toDeploymentResponse(retry, versionLabelOf(retry)));
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
                reportDeploymentStatusRequest.getLogsUrl(),
                reportDeploymentStatusRequest.getErrorMessage()
        );
        return ResponseEntity.ok(PocDeploymentResponseMapper.toDeploymentResponse(deployment, versionLabelOf(deployment)));
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

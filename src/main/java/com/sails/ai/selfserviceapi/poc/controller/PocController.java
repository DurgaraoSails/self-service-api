package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.deployment.entity.PocDeployment;
import com.sails.ai.selfserviceapi.deployment.exception.PocDeploymentNotFoundException;
import com.sails.ai.selfserviceapi.deployment.repository.PocDeploymentRepository;
import com.sails.ai.selfserviceapi.deployment.service.CheckUpdatesService;
import com.sails.ai.selfserviceapi.deployment.service.CheckUpdatesService.CheckUpdatesResult;
import com.sails.ai.selfserviceapi.deployment.service.DeploymentOrchestrator;
import com.sails.ai.selfserviceapi.deployment.service.PocDeploymentResponseMapper;
import com.sails.ai.selfserviceapi.deployment.service.VersionService.BumpType;
import com.sails.ai.selfserviceapi.generated.api.PocApi;
import com.sails.ai.selfserviceapi.generated.model.CheckUpdatesResponse;
import com.sails.ai.selfserviceapi.generated.model.CreatePocRequest;
import com.sails.ai.selfserviceapi.generated.model.DeployNewVersionRequest;
import com.sails.ai.selfserviceapi.generated.model.PocDeploymentResponse;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdatePocRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.service.PocFields;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocController implements PocApi {

    private final PocService pocService;
    private final DeploymentOrchestrator deploymentOrchestrator;
    private final CheckUpdatesService checkUpdatesService;
    private final PocDeploymentRepository pocDeploymentRepository;

    public PocController(
            PocService pocService,
            DeploymentOrchestrator deploymentOrchestrator,
            CheckUpdatesService checkUpdatesService,
            PocDeploymentRepository pocDeploymentRepository) {
        this.pocService = pocService;
        this.deploymentOrchestrator = deploymentOrchestrator;
        this.checkUpdatesService = checkUpdatesService;
        this.pocDeploymentRepository = pocDeploymentRepository;
    }

    @Override
    public ResponseEntity<List<PocSummaryResponse>> getPocs(Boolean includeDeleted) {
        boolean isAdmin = CurrentUser.isAdmin();
        List<PocSummaryResponse> pocs = pocService.listForViewer(isAdmin, isAdmin && Boolean.TRUE.equals(includeDeleted)).stream()
                .map(PocResponseMapper::toSummaryResponse)
                .toList();
        return ResponseEntity.ok(pocs);
    }

    @Override
    public ResponseEntity<PocResponse> getPocById(Long id) {
        Poc poc = pocService.getById(id);
        return ResponseEntity.ok(PocResponseMapper.toResponse(poc));
    }

    /**
     * Creating a POC always kicks off the deploy pipeline — deploymentStatus starts
     * "not_deployed" and DeploymentOrchestrator takes over asynchronously from here, so this
     * returns 201 immediately rather than waiting on the tag/build/deploy sequence.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> createPoc(CreatePocRequest createPocRequest) {
        PocFields fields = new PocFields(
                createPocRequest.getName(),
                createPocRequest.getDescription(),
                createPocRequest.getIconUrl(),
                createPocRequest.getAppUrl(),
                createPocRequest.getGithubUrl(),
                createPocRequest.getVersion(),
                createPocRequest.getOwner(),
                createPocRequest.getCategory(),
                createPocRequest.getTechnologies(),
                createPocRequest.getContainerImage(),
                createPocRequest.getDemoType(),
                createPocRequest.getStatus() != null ? createPocRequest.getStatus().getValue() : null,
                createPocRequest.getDetails(),
                createPocRequest.getGuideSteps()
        );
        Poc poc = pocService.createForPipeline(fields, createPocRequest.getSlug());
        deploymentOrchestrator.triggerInitialDeployment(poc.getId(), CurrentUser.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(PocResponseMapper.toResponse(poc));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> updatePoc(Long id, UpdatePocRequest updatePocRequest) {
        PocFields fields = new PocFields(
                updatePocRequest.getName(),
                updatePocRequest.getDescription(),
                updatePocRequest.getIconUrl(),
                updatePocRequest.getAppUrl(),
                updatePocRequest.getGithubUrl(),
                updatePocRequest.getVersion(),
                updatePocRequest.getOwner(),
                updatePocRequest.getCategory(),
                updatePocRequest.getTechnologies(),
                updatePocRequest.getContainerImage(),
                updatePocRequest.getDemoType(),
                updatePocRequest.getStatus() != null ? updatePocRequest.getStatus().getValue() : null,
                updatePocRequest.getDetails(),
                updatePocRequest.getGuideSteps()
        );
        Poc poc = pocService.update(id, fields);
        return ResponseEntity.ok(PocResponseMapper.toResponse(poc));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePoc(Long id) {
        pocService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> hidePoc(Long id) {
        return ResponseEntity.ok(PocResponseMapper.toResponse(pocService.hide(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> unhidePoc(Long id) {
        return ResponseEntity.ok(PocResponseMapper.toResponse(pocService.unhide(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> restorePoc(Long id) {
        return ResponseEntity.ok(PocResponseMapper.toResponse(pocService.restore(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<CheckUpdatesResponse> checkPocUpdates(String slug) {
        Poc poc = pocService.getBySlug(slug);
        CheckUpdatesResult result = checkUpdatesService.checkForUpdates(poc);
        CheckUpdatesResponse response = new CheckUpdatesResponse(result.updateAvailable())
                .latestMainCommitSha(result.latestMainCommitSha())
                .deployedCommitSha(result.deployedCommitSha());
        return ResponseEntity.ok(response);
    }

    /** Runs asynchronously — 202 just confirms the pipeline was triggered, not that it finished. */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deployNewPocVersion(String slug, DeployNewVersionRequest deployNewVersionRequest) {
        Poc poc = pocService.getBySlug(slug);
        BumpType bumpType = toBumpType(deployNewVersionRequest);
        deploymentOrchestrator.triggerNewVersion(poc.getId(), bumpType, CurrentUser.id());
        return ResponseEntity.accepted().build();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocDeploymentResponse> getLatestPocDeployment(String slug) {
        Poc poc = pocService.getBySlug(slug);
        PocDeployment deployment = pocDeploymentRepository.findFirstByPocIdOrderByCreatedAtDesc(poc.getId())
                .orElseThrow(() -> new PocDeploymentNotFoundException(slug));
        return ResponseEntity.ok(PocDeploymentResponseMapper.toResponse(deployment));
    }

    private static BumpType toBumpType(DeployNewVersionRequest request) {
        if (request == null || request.getBump() == null) {
            return BumpType.MINOR;
        }
        return BumpType.valueOf(request.getBump().getValue().toUpperCase());
    }
}

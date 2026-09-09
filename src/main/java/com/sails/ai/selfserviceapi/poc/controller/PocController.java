package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubBranches;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubService;
import com.sails.ai.selfserviceapi.generated.api.PocApi;
import com.sails.ai.selfserviceapi.generated.model.CreatePocRequest;
import com.sails.ai.selfserviceapi.generated.model.PocBranchesResponse;
import com.sails.ai.selfserviceapi.generated.model.PocCategoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdatePocRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import com.sails.ai.selfserviceapi.poc.service.PocFields;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocController implements PocApi {

    private final PocService pocService;
    private final PocDeploymentService pocDeploymentService;
    private final GitHubService gitHubService;

    public PocController(PocService pocService, PocDeploymentService pocDeploymentService,
                          GitHubService gitHubService) {
        this.pocService = pocService;
        this.pocDeploymentService = pocDeploymentService;
        this.gitHubService = gitHubService;
    }

    /**
     * Keyed on the repository URL rather than a POC id because the create form needs this before a
     * POC exists — the admin types a GitHub URL and then picks a branch from it. The settings page
     * passes the URL of the POC it already loaded, so one endpoint serves both.
     */
    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocBranchesResponse> getPocBranches(String githubUrl) {
        GitHubRepoRef repo = gitHubService.parseRepoUrl(githubUrl);
        GitHubBranches branches = gitHubService.listBranches(repo);
        return ResponseEntity.ok(new PocBranchesResponse(branches.names())
                .defaultBranch(gitHubService.getDefaultBranch(repo))
                .truncated(branches.truncated()));
    }

    @Override
    public ResponseEntity<List<PocSummaryResponse>> getPocs(Boolean includeDeleted) {
        boolean isAdmin = CurrentUser.isAdmin();
        List<Poc> pocs = pocService.listForViewer(isAdmin, isAdmin && Boolean.TRUE.equals(includeDeleted));

        List<UUID> versionIds = pocs.stream().map(Poc::getActiveVersionId).filter(Objects::nonNull).toList();
        List<UUID> pocIds = pocs.stream().map(Poc::getId).toList();
        Map<UUID, String> activeVersionLabels = pocDeploymentService.activeVersionLabels(versionIds);
        Map<UUID, String> latestStatuses = pocDeploymentService.latestDeploymentStatuses(pocIds);

        List<PocSummaryResponse> pocResponses = pocs.stream()
                .map(poc -> {
                    UUID activeVersionId = poc.getActiveVersionId();
                    String activeVersionLabel = activeVersionId != null ? activeVersionLabels.get(activeVersionId) : null;
                    return PocResponseMapper.toSummaryResponse(poc, activeVersionLabel, latestStatuses.get(poc.getId()));
                })
                .toList();
        return ResponseEntity.ok(pocResponses);
    }

    @Override
    public ResponseEntity<List<PocCategoryResponse>> getPocCategories() {
        List<PocCategoryResponse> categories = pocService.listCategories().stream()
                .map(PocResponseMapper::toCategoryResponse)
                .toList();
        return ResponseEntity.ok(categories);
    }

    @Override
    public ResponseEntity<PocResponse> getPocById(UUID id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.getById(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> createPoc(CreatePocRequest createPocRequest) {
        PocFields fields = new PocFields(
                createPocRequest.getName(),
                createPocRequest.getDescription(),
                createPocRequest.getSlug(),
                createPocRequest.getIconUrl(),
                createPocRequest.getAppUrl(),
                createPocRequest.getGithubUrl(),
                createPocRequest.getDeployBranch(),
                createPocRequest.getOwner(),
                createPocRequest.getCategory(),
                createPocRequest.getTechnologies(),
                createPocRequest.getDemoType(),
                createPocRequest.getVisibilityStatus() != null ? createPocRequest.getVisibilityStatus().getValue() : null,
                createPocRequest.getDetails(),
                createPocRequest.getGuideSteps()
        );
        Poc poc = pocService.create(fields);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseWithDeploymentInfo(poc));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> updatePoc(UUID id, UpdatePocRequest updatePocRequest) {
        PocFields fields = new PocFields(
                updatePocRequest.getName(),
                updatePocRequest.getDescription(),
                updatePocRequest.getSlug(),
                updatePocRequest.getIconUrl(),
                updatePocRequest.getAppUrl(),
                updatePocRequest.getGithubUrl(),
                updatePocRequest.getDeployBranch(),
                updatePocRequest.getOwner(),
                updatePocRequest.getCategory(),
                updatePocRequest.getTechnologies(),
                updatePocRequest.getDemoType(),
                updatePocRequest.getVisibilityStatus() != null ? updatePocRequest.getVisibilityStatus().getValue() : null,
                updatePocRequest.getDetails(),
                updatePocRequest.getGuideSteps()
        );
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.update(id, fields)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deletePoc(UUID id) {
        pocService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> hidePoc(UUID id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.hide(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> unhidePoc(UUID id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.unhide(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> restorePoc(UUID id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.restore(id)));
    }

    private PocResponse toResponseWithDeploymentInfo(Poc poc) {
        String activeVersionLabel = poc.getActiveVersionId() != null
                ? pocDeploymentService.activeVersionLabels(List.of(poc.getActiveVersionId())).get(poc.getActiveVersionId())
                : null;
        String latestStatus = pocDeploymentService.latestDeploymentStatuses(List.of(poc.getId())).get(poc.getId());
        return PocResponseMapper.toResponse(poc, activeVersionLabel, latestStatus);
    }
}

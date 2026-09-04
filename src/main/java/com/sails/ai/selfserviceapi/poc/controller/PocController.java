package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.generated.api.PocApi;
import com.sails.ai.selfserviceapi.generated.model.CreatePocRequest;
import com.sails.ai.selfserviceapi.generated.model.PocCategoryResponse;
import com.sails.ai.selfserviceapi.generated.model.PocLaunchResponse;
import com.sails.ai.selfserviceapi.generated.model.PocLaunchResponseUser;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.ThemeMode;
import com.sails.ai.selfserviceapi.generated.model.UpdatePocRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.service.PocDeploymentService;
import com.sails.ai.selfserviceapi.poc.service.PocFields;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import com.sails.ai.selfserviceapi.security.JwtService;
import com.sails.ai.selfserviceapi.user.entity.User;
import com.sails.ai.selfserviceapi.user.service.UserService;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocController implements PocApi {

    private final PocService pocService;
    private final PocDeploymentService pocDeploymentService;
    private final UserService userService;
    private final JwtService jwtService;

    public PocController(PocService pocService, PocDeploymentService pocDeploymentService,
                          UserService userService, JwtService jwtService) {
        this.pocService = pocService;
        this.pocDeploymentService = pocDeploymentService;
        this.userService = userService;
        this.jwtService = jwtService;
    }

    @Override
    public ResponseEntity<List<PocSummaryResponse>> getPocs(Boolean includeDeleted) {
        boolean isAdmin = CurrentUser.isAdmin();
        List<Poc> pocs = pocService.listForViewer(isAdmin, isAdmin && Boolean.TRUE.equals(includeDeleted));

        List<Long> versionIds = pocs.stream().map(Poc::getActiveVersionId).filter(Objects::nonNull).toList();
        List<Long> pocIds = pocs.stream().map(Poc::getId).toList();
        Map<Long, String> activeVersionLabels = pocDeploymentService.activeVersionLabels(versionIds);
        Map<Long, String> latestStatuses = pocDeploymentService.latestDeploymentStatuses(pocIds);

        List<PocSummaryResponse> pocResponses = pocs.stream()
                .map(poc -> {
                    Long activeVersionId = poc.getActiveVersionId();
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
    public ResponseEntity<PocResponse> getPocById(Long id) {
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
    public ResponseEntity<PocResponse> updatePoc(Long id, UpdatePocRequest updatePocRequest) {
        PocFields fields = new PocFields(
                updatePocRequest.getName(),
                updatePocRequest.getDescription(),
                updatePocRequest.getSlug(),
                updatePocRequest.getIconUrl(),
                updatePocRequest.getAppUrl(),
                updatePocRequest.getGithubUrl(),
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
    public ResponseEntity<Void> deletePoc(Long id) {
        pocService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> hidePoc(Long id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.hide(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> unhidePoc(Long id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.unhide(id)));
    }

    @Override
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<PocResponse> restorePoc(Long id) {
        return ResponseEntity.ok(toResponseWithDeploymentInfo(pocService.restore(id)));
    }

    @Override
    public ResponseEntity<PocLaunchResponse> launchPoc(String slug) {
        User user = userService.getById(CurrentUser.id());
        Poc poc = pocService.getLaunchable(slug, CurrentUser.isAdmin());
        String token = jwtService.issuePocToken(user, poc.getSlug());
        Instant expiresAt = Instant.now().plusSeconds(jwtService.pocTokenTtlSeconds());

        PocLaunchResponse response = new PocLaunchResponse(
                token,
                expiresAt.atOffset(ZoneOffset.UTC),
                poc.getAppUrl(),
                new PocLaunchResponseUser(user.getId(), user.getDisplayName()),
                ThemeMode.valueOf(user.getTheme().name())
        );
        return ResponseEntity.ok(response);
    }

    private PocResponse toResponseWithDeploymentInfo(Poc poc) {
        String activeVersionLabel = poc.getActiveVersionId() != null
                ? pocDeploymentService.activeVersionLabels(List.of(poc.getActiveVersionId())).get(poc.getActiveVersionId())
                : null;
        String latestStatus = pocDeploymentService.latestDeploymentStatuses(List.of(poc.getId())).get(poc.getId());
        return PocResponseMapper.toResponse(poc, activeVersionLabel, latestStatus);
    }
}

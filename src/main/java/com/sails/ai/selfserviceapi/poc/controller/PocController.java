package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.generated.api.PocApi;
import com.sails.ai.selfserviceapi.generated.api.PocSessionsApi;
import com.sails.ai.selfserviceapi.generated.model.CreatePocRequest;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSessionResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdatePocRequest;
import com.sails.ai.selfserviceapi.poc.config.PocGatewayProperties;
import com.sails.ai.selfserviceapi.poc.entity.DemoSession;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.service.DemoSessionService;
import com.sails.ai.selfserviceapi.poc.service.PocFields;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import com.sails.ai.selfserviceapi.security.CurrentUser;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import com.sails.ai.selfserviceapi.security.JwtService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class PocController implements PocApi, PocSessionsApi {

    private static final String LIVE_STATUS = "ACTIVE";

    private final PocService pocService;
    private final JwtService jwtService;
    private final DemoSessionService demoSessionService;
    private final PocGatewayProperties pocGatewayProperties;

    public PocController(PocService pocService,
                         JwtService jwtService,
                         DemoSessionService demoSessionService,
                         PocGatewayProperties pocGatewayProperties) {
        this.pocService = pocService;
        this.jwtService = jwtService;
        this.demoSessionService = demoSessionService;
        this.pocGatewayProperties = pocGatewayProperties;
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
                createPocRequest.getEmbedMode() != null ? createPocRequest.getEmbedMode().getValue() : null,
                createPocRequest.getDetails(),
                createPocRequest.getGuideSteps()
        );
        Poc poc = pocService.create(fields);
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
                updatePocRequest.getEmbedMode() != null ? updatePocRequest.getEmbedMode().getValue() : null,
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
    public ResponseEntity<PocSessionResponse> launchPocSession(String slug) {
        String userId = CurrentUser.id();

        Poc poc = pocService.getBySlug(slug);

        if (!LIVE_STATUS.equals(poc.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "POC is not currently live (status: " + poc.getStatus() + ")");
        }

        Instant expiresAt = jwtService.computeExpiry();

        DemoSession session = new DemoSession();
        session.setPocId(poc.getId());
        session.setUserId(userId);
        session.setStatus("confirmed");
        session.setExpiresAt(expiresAt);
        session = demoSessionService.save(session); // saved first so we have an id for the "sid" claim

        String token = jwtService.mintPocAccessToken(userId, slug, session.getId(), expiresAt, poc.getEmbedMode());

        session.setAccessToken(token);
        demoSessionService.save(session);

        URI publicUrl = URI.create("https://" + slug + "." + pocGatewayProperties.domain());

        return ResponseEntity.ok(new PocSessionResponse(
                publicUrl,
                token,
                expiresAt.atOffset(ZoneOffset.UTC),
                session.getId()
        ));
    }
}

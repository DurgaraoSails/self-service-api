package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.generated.api.PocApi;
import com.sails.ai.selfserviceapi.generated.model.CreatePocRequest;
import com.sails.ai.selfserviceapi.generated.model.PocResponse;
import com.sails.ai.selfserviceapi.generated.model.PocSummaryResponse;
import com.sails.ai.selfserviceapi.generated.model.UpdatePocRequest;
import com.sails.ai.selfserviceapi.poc.entity.Poc;
import com.sails.ai.selfserviceapi.poc.service.PocResponseMapper;
import com.sails.ai.selfserviceapi.poc.service.PocService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocController implements PocApi {

    private final PocService pocService;

    public PocController(PocService pocService) {
        this.pocService = pocService;
    }

    @Override
    public ResponseEntity<List<PocSummaryResponse>> getPocs() {
        List<PocSummaryResponse> pocs = pocService.listAll().stream()
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
    public ResponseEntity<PocResponse> createPoc(CreatePocRequest createPocRequest) {
        Poc poc = pocService.create(
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
                createPocRequest.getStatus()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(PocResponseMapper.toResponse(poc));
    }

    @Override
    public ResponseEntity<PocResponse> updatePoc(Long id, UpdatePocRequest updatePocRequest) {
        Poc poc = pocService.update(
                id,
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
                updatePocRequest.getStatus()
        );
        return ResponseEntity.ok(PocResponseMapper.toResponse(poc));
    }

    @Override
    public ResponseEntity<Void> deletePoc(Long id) {
        pocService.delete(id);
        return ResponseEntity.noContent().build();
    }
}

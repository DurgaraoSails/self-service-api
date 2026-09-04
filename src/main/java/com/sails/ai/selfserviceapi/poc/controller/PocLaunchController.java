package com.sails.ai.selfserviceapi.poc.controller;

import com.sails.ai.selfserviceapi.generated.api.PocLaunchApi;
import com.sails.ai.selfserviceapi.generated.model.PocLaunchResponse;
import com.sails.ai.selfserviceapi.poc.service.PocLaunchService;
import com.sails.ai.selfserviceapi.security.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocLaunchController implements PocLaunchApi {

    private final PocLaunchService pocLaunchService;

    public PocLaunchController(PocLaunchService pocLaunchService) {
        this.pocLaunchService = pocLaunchService;
    }

    @Override
    public ResponseEntity<PocLaunchResponse> launchPoc(String slug) {
        PocLaunchService.PocLaunch launch = pocLaunchService.launch(slug, CurrentUser.id());

        return ResponseEntity.ok(new PocLaunchResponse(
                launch.token(),
                launch.expiresInSeconds(),
                launch.launchUrl(),
                launch.pocId(),
                launch.slug()));
    }
}

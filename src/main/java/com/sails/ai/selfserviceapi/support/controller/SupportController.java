package com.sails.ai.selfserviceapi.support.controller;

import com.sails.ai.selfserviceapi.generated.api.SupportApi;
import com.sails.ai.selfserviceapi.generated.model.ContactSalesRequest;
import com.sails.ai.selfserviceapi.generated.model.ContactSalesResponse;
import com.sails.ai.selfserviceapi.support.service.SupportService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SupportController implements SupportApi {

    private final SupportService supportService;

    public SupportController(SupportService supportService) {
        this.supportService = supportService;
    }

    @Override
    public ResponseEntity<ContactSalesResponse> contactSales(ContactSalesRequest contactSalesRequest) {
        supportService.contactSales(currentUserId(), contactSalesRequest.getMessage());
        return ResponseEntity.ok(new ContactSalesResponse("Thanks! Our sales team will be in touch shortly."));
    }

    private String currentUserId() {
        Jwt jwt = (Jwt) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return jwt.getSubject();
    }
}

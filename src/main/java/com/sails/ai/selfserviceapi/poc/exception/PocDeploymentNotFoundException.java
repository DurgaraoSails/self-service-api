package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PocDeploymentNotFoundException extends ApiException {

    public PocDeploymentNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "POC_DEPLOYMENT_NOT_FOUND", "POC deployment not found: " + id);
    }
}

package com.sails.ai.selfserviceapi.deployment.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PocDeploymentNotFoundException extends ApiException {

    public PocDeploymentNotFoundException(String slug) {
        super(HttpStatus.NOT_FOUND, "POC_DEPLOYMENT_NOT_FOUND", "No deployment recorded yet for POC: " + slug);
    }
}

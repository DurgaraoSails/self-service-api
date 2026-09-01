package com.sails.ai.selfserviceapi.deployment.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class DeploymentAlreadyInProgressException extends ApiException {

    public DeploymentAlreadyInProgressException(String slug) {
        super(HttpStatus.CONFLICT, "DEPLOYMENT_ALREADY_IN_PROGRESS",
                "A deployment is already in progress for POC '" + slug + "'. Wait for it to finish before starting another.");
    }
}

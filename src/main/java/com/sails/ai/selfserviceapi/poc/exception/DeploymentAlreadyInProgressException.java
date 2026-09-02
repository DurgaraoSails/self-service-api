package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** A POC may have at most one non-terminal deployment at a time. */
public class DeploymentAlreadyInProgressException extends ApiException {

    public DeploymentAlreadyInProgressException(Long pocId) {
        super(HttpStatus.CONFLICT, "DEPLOYMENT_ALREADY_IN_PROGRESS",
                "POC " + pocId + " already has a deployment in progress. Wait for it to finish before starting another.");
    }
}

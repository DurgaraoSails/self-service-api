package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Only a FAILED deployment can be retried — a running or already-successful one cannot. */
public class DeploymentNotRetryableException extends ApiException {

    public DeploymentNotRetryableException(UUID deploymentId, String currentStatus) {
        super(HttpStatus.CONFLICT, "DEPLOYMENT_NOT_RETRYABLE",
                "Deployment " + deploymentId + " is " + currentStatus + ", not FAILED — nothing to retry.");
    }
}

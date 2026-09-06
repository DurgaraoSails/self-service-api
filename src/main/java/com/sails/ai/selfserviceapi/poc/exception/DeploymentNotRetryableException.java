package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Only a FAILED deployment can be retried, and only if it's still the most recent deployment for
 * its POC — a newer deployment (of any status) since superseded it, and retrying the old one would
 * resurrect an attempt the admin has already moved past.
 */
public class DeploymentNotRetryableException extends ApiException {

    public DeploymentNotRetryableException(UUID deploymentId, String currentStatus) {
        super(HttpStatus.CONFLICT, "DEPLOYMENT_NOT_RETRYABLE",
                "Deployment " + deploymentId + " is " + currentStatus + ", not FAILED — nothing to retry.");
    }

    public static DeploymentNotRetryableException supersededByANewerDeployment(UUID deploymentId) {
        return new DeploymentNotRetryableException(
                "Deployment " + deploymentId + " is no longer the most recent deployment for this POC — "
                        + "only the latest deployment can be retried.");
    }

    private DeploymentNotRetryableException(String message) {
        super(HttpStatus.CONFLICT, "DEPLOYMENT_NOT_RETRYABLE", message);
    }
}

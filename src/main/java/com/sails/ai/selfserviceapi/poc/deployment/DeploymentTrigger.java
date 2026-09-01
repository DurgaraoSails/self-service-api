package com.sails.ai.selfserviceapi.poc.deployment;

/**
 * Kicks off the actual container build/deploy pipeline (owned by a separate team, not yet built).
 * self-service-api never calls GCP directly — the implementation of this interface is whatever
 * triggers the pipeline (webhook call, queue publish, etc.); progress is reported back
 * asynchronously via POST /pocs/deployments/{deploymentId}/status.
 */
public interface DeploymentTrigger {

    void buildAndDeploy(BuildAndDeployRequest request);

    void redeploy(RedeployRequest request);
}

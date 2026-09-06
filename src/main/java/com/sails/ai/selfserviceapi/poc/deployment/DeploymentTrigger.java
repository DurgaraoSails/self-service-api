package com.sails.ai.selfserviceapi.poc.deployment;

/**
 * Kicks off the actual container build/deploy pipeline. The default implementation,
 * {@code InProcessDeploymentTrigger}, runs it in this same JVM via {@code PipelineRunner}, which
 * calls GCP (Cloud Build/Cloud Run) directly — there is no separate pipeline service. Progress is
 * reported back via direct calls to {@code PocDeploymentService}; the webhook-based
 * POST /pocs/deployments/{deploymentId}/status contract still exists for a genuinely external
 * caller (or the manual-testing {@code LoggingDeploymentTrigger} stub), but nothing in the
 * in-process path uses it.
 */
public interface DeploymentTrigger {

    void buildAndDeploy(BuildAndDeployRequest request);

    void redeploy(RedeployRequest request);
}

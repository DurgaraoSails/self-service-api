package com.sails.ai.selfserviceapi.poc.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Stub trigger used until the real pipeline exists. Deliberately stays inert rather than
 * auto-advancing status on a timer — the intended test loop is manual (click Deploy, then curl
 * the status callback through BUILDING/DEPLOYING/SUCCEEDED yourself), and a timer would race
 * that, making it non-deterministic. Logs a ready-to-paste curl command for exactly that.
 */
@Component
@ConditionalOnProperty(prefix = "deployment", name = "trigger", havingValue = "logging")
public class LoggingDeploymentTrigger implements DeploymentTrigger {

    private static final Logger log = LoggerFactory.getLogger(LoggingDeploymentTrigger.class);

    @Override
    public void buildAndDeploy(BuildAndDeployRequest request) {
        log.info("""
                        [STUB] buildAndDeploy: deploymentId={} pocId={} githubUrl={} versionLabel={}
                        No real pipeline wired yet. Simulate progress with:
                          curl -X POST http://localhost:8080/api/v1/pocs/deployments/{}/status \
                        -H "X-Pipeline-Webhook-Secret: <secret>" -H "Content-Type: application/json" \
                        -d '{"status":"BUILDING"}'""",
                request.deploymentId(), request.pocId(), request.githubUrl(), request.versionLabel(), request.deploymentId());
    }

    @Override
    public void redeploy(RedeployRequest request) {
        log.info("""
                        [STUB] redeploy: deploymentId={} pocId={} imagesByContainer={} versionLabel={}
                        No real pipeline wired yet. Simulate progress via POST .../status as above \
                        (typically just DEPLOYING then SUCCEEDED, since the images already exist).""",
                request.deploymentId(), request.pocId(), request.imagesByContainer(), request.versionLabel());
    }
}

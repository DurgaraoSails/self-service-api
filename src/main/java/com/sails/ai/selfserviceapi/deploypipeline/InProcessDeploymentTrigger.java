package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.poc.deployment.BuildAndDeployRequest;
import com.sails.ai.selfserviceapi.poc.deployment.DeploymentTrigger;
import com.sails.ai.selfserviceapi.poc.deployment.RedeployRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The real implementation of {@link DeploymentTrigger}: hands off to {@link PipelineRunner},
 * which does the work asynchronously in this same process. Default trigger — see
 * {@code LoggingDeploymentTrigger} for the manual-testing stub this replaces.
 */
@Component
@ConditionalOnProperty(prefix = "deployment", name = "trigger", havingValue = "in-process", matchIfMissing = true)
public class InProcessDeploymentTrigger implements DeploymentTrigger {

    private final PipelineRunner pipelineRunner;

    public InProcessDeploymentTrigger(PipelineRunner pipelineRunner) {
        this.pipelineRunner = pipelineRunner;
    }

    @Override
    public void buildAndDeploy(BuildAndDeployRequest request) {
        pipelineRunner.runBuildAndDeploy(
                request.deploymentId(), request.pocSlug(), request.githubUrl(), request.versionLabel());
    }

    @Override
    public void redeploy(RedeployRequest request) {
        pipelineRunner.runRedeploy(request.deploymentId(), request.pocSlug(),
                request.imagesByContainer(), request.manifestYaml(), request.versionLabel());
    }
}

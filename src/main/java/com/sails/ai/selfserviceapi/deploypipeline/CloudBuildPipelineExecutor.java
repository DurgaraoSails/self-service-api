package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.build.BuildService;
import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import com.sails.ai.selfserviceapi.deploypipeline.run.CloudRunService;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Runs the pipeline on Google's infrastructure via the Cloud Build API. The only executor that
 * works once self-service-api itself runs on Cloud Run — there is no Docker daemon there for the
 * local executor to shell out to.
 */
@Component
@ConditionalOnProperty(prefix = "pipeline", name = "executor", havingValue = "cloud-build")
public class CloudBuildPipelineExecutor implements PipelineExecutor {

    private final BuildService buildService;
    private final CloudRunService cloudRunService;

    public CloudBuildPipelineExecutor(BuildService buildService, CloudRunService cloudRunService) {
        this.buildService = buildService;
        this.cloudRunService = cloudRunService;
    }

    @Override
    public Map<String, String> buildAndPushImages(GitHubRepoRef repo, String versionLabel, String pocSlug, PocManifest manifest) {
        return buildService.buildAndPushAll(repo, versionLabel, pocSlug, manifest);
    }

    @Override
    public String deploy(String pocSlug, String versionLabel, PocManifest manifest, Map<String, String> imagesByContainer) {
        buildService.deploy(pocSlug, versionLabel, manifest, imagesByContainer);
        return cloudRunService.getServiceUrl(pocSlug);
    }
}

package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;
import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.Map;

/**
 * Where the actual build and deploy work happens — the one seam between the two executors. Both
 * calls block until the underlying work finishes; PipelineRunner is what makes the whole thing
 * asynchronous from the caller's point of view.
 */
public interface PipelineExecutor {

    /**
     * Clones the given version's tag once, then builds and pushes one image per container the
     * manifest declares. Returns each pushed image's URI, keyed by container name.
     */
    Map<String, String> buildAndPushImages(GitHubRepoRef repo, String versionLabel, String pocSlug, PocManifest manifest);

    /**
     * Deploys every container (freshly built or pre-existing) as one Cloud Run service and returns
     * the resulting hosted URL. {@code imagesByContainer} is keyed by the manifest's container names.
     */
    String deploy(String pocSlug, PocManifest manifest, Map<String, String> imagesByContainer);
}

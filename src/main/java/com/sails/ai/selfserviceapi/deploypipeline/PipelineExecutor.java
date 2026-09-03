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
     * Clones the given version's tag once, builds every container the manifest declares, and
     * pushes each image. Returns the pushed image URI keyed by container name — one entry per
     * {@code manifest.containers()}.
     */
    Map<String, String> buildAndPushImages(GitHubRepoRef repo, String versionLabel, String pocSlug, PocManifest manifest);

    /**
     * Deploys every image (freshly built, or a version's previously-built ones for a redeploy) as
     * one Cloud Run service — the manifest's ingress container plus its sidecars. Returns the
     * resulting hosted URL.
     */
    String deploy(String pocSlug, String versionLabel, PocManifest manifest, Map<String, String> imagesByContainer);
}

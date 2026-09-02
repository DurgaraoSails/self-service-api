package com.sails.ai.selfserviceapi.deploypipeline;

import com.sails.ai.selfserviceapi.deploypipeline.github.GitHubRepoRef;

/**
 * Where the actual build and deploy work happens — the one seam between the two executors. Both
 * calls block until the underlying work finishes; PipelineRunner is what makes the whole thing
 * asynchronous from the caller's point of view.
 */
public interface PipelineExecutor {

    /** Clones the given version's tag, builds an image, and pushes it. Returns the pushed image URI. */
    String buildAndPushImage(GitHubRepoRef repo, String versionLabel, String pocSlug);

    /** Deploys an image (freshly built or pre-existing) and returns the resulting hosted URL. */
    String deploy(String pocSlug, String image);
}

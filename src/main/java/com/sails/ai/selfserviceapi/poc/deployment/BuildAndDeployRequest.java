package com.sails.ai.selfserviceapi.poc.deployment;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.UUID;

/**
 * {@code commitSha} and {@code manifest} are resolved once, synchronously, in
 * {@code PocDeploymentService.deployNewVersion} (before this deployment row — or its version's
 * {@code manifestYaml} — was ever created) and threaded through here so the async pipeline run
 * never has to re-resolve either: no second GitHub call for the commit, and no risk of deploying
 * a different manifest than the one that was actually validated and stored.
 *
 * <p>Both are null when {@code pipeline.executor=skip} — skip mode never touches GitHub at all,
 * consistent with its existing contract.
 *
 * @param createTag false for "deploy an existing tag" — the tag is that request's input, not its
 *                  output, so the pipeline must not write it or demand push access to do so. True
 *                  for "deploy new version," where the platform derived {@code versionLabel} and
 *                  the tag does not exist yet.
 */
public record BuildAndDeployRequest(
        UUID deploymentId,
        UUID pocId,
        String pocSlug,
        String githubUrl,
        String versionLabel,
        String commitSha,
        PocManifest manifest,
        boolean createTag
) {
}

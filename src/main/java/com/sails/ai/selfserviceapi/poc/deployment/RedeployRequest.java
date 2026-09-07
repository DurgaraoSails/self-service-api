package com.sails.ai.selfserviceapi.poc.deployment;

import com.sails.ai.selfserviceapi.deploypipeline.manifest.PocManifest;
import java.util.Map;
import java.util.UUID;

/**
 * {@code manifest} is resolved from the version's stored {@code manifestYaml} — never a fresh
 * GitHub read — and {@code imagesByContainer} comes from that version's persisted
 * {@code PocVersionContainer} rows, falling back to {@code {"app": <the version's containerImage>}}
 * for a pre-manifest version with no such rows. See
 * {@code PocDeploymentService.resolveImagesByContainer}.
 */
public record RedeployRequest(
        UUID deploymentId,
        Long pocId,
        String pocSlug,
        String versionLabel,
        PocManifest manifest,
        Map<String, String> imagesByContainer
) {
}

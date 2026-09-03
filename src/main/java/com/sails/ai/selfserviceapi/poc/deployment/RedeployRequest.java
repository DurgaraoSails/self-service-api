package com.sails.ai.selfserviceapi.poc.deployment;

import java.util.Map;
import java.util.UUID;

/**
 * {@code imagesByContainer} carries every container's already-built image for this version — not
 * just the ingress one — so a multi-container version redeploys all of it, not only its front
 * door. {@code manifestYaml} is the version's stored manifest (or null, for a pre-manifest
 * version), so a redeploy reconstructs exactly what was deployed then rather than re-reading
 * whatever poc.yaml says in the repo today.
 */
public record RedeployRequest(
        UUID deploymentId,
        Long pocId,
        String pocSlug,
        Map<String, String> imagesByContainer,
        String manifestYaml,
        String versionLabel
) {
}

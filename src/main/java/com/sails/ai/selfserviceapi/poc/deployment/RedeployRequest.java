package com.sails.ai.selfserviceapi.poc.deployment;

import java.util.UUID;

public record RedeployRequest(
        UUID deploymentId,
        Long pocId,
        String pocSlug,
        String containerImage,
        String versionLabel
) {
}

package com.sails.ai.selfserviceapi.poc.deployment;

import java.util.UUID;

public record BuildAndDeployRequest(
        UUID deploymentId,
        Long pocId,
        String pocSlug,
        String githubUrl,
        String versionLabel
) {
}

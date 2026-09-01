package com.sails.ai.selfserviceapi.poc.deployment;

import java.util.UUID;

public record BuildAndDeployRequest(
        UUID deploymentId,
        Long pocId,
        String githubUrl,
        String versionLabel
) {
}

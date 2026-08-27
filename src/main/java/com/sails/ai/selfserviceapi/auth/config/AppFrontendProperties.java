package com.sails.ai.selfserviceapi.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base URL of self-service-portal, used to build links embedded in outbound emails. */
@ConfigurationProperties(prefix = "app.frontend")
public record AppFrontendProperties(
        String url
) {
}

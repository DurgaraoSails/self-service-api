package com.sails.ai.selfserviceapi.poc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Shared secret the deploy pipeline authenticates with when calling the status callback. */
@ConfigurationProperties(prefix = "deployment")
public record DeploymentWebhookProperties(
        String webhookSecret
) {
}

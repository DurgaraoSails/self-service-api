package com.sails.ai.selfserviceapi.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "email-domain-validation")
public record EmailDomainValidationProperties(
        int timeoutMillis,
        int retries
) {
}

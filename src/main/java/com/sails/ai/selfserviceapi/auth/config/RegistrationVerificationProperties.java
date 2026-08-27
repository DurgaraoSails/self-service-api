package com.sails.ai.selfserviceapi.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "registration-verification")
public record RegistrationVerificationProperties(
        int ttlHours
) {
}

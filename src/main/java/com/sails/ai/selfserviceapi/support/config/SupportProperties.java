package com.sails.ai.selfserviceapi.support.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "support")
public record SupportProperties(
        String salesEmail
) {
}

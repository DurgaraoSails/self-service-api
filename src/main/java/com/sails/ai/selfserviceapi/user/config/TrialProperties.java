package com.sails.ai.selfserviceapi.user.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "trial")
public record TrialProperties(
        int lengthDays
) {
}

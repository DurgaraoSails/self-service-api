package com.sails.ai.selfserviceapi.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "otp")
public record OtpProperties(
        String hashSecret,
        int expiryMinutes,
        int maxAttempts,
        int maxResends,
        int maxRequestsPerHour
) {
}

package com.sails.ai.selfserviceapi.poc.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Base domain POC public URLs are minted under, e.g. {@code <slug>.<domain>}. */
@ConfigurationProperties(prefix = "app.poc")
public record PocGatewayProperties(
        String domain
) {
}

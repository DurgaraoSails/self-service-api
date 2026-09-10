package com.sails.ai.selfserviceapi.asset.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Independent feature flags. Keyword search and manual review must work with both aiEnabled and
 * semanticSearchEnabled false — that combination is the safe default for every existing deployment.
 */
@ConfigurationProperties(prefix = "asset-hub")
public record AssetHubProperties(
        boolean enabled,
        boolean aiEnabled,
        boolean semanticSearchEnabled
) {
}

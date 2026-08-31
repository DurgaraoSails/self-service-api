package com.sails.ai.selfserviceapi.deployment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gcp")
public record GcpProperties(
        String projectId,
        String region,
        String environment
) {

    public String serviceAccountResourceName(String accountId) {
        return "projects/%s/serviceAccounts/%s-%s@%s.iam.gserviceaccount.com"
                .formatted(projectId, accountId, environment, projectId);
    }

    public String serviceAccountEmail(String accountId) {
        return "%s-%s@%s.iam.gserviceaccount.com".formatted(accountId, environment, projectId);
    }

    public String secretVersionName(String secretId) {
        return "projects/%s/secrets/%s-%s/versions/latest".formatted(projectId, secretId, environment);
    }
}

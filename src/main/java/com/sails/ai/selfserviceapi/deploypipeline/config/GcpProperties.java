package com.sails.ai.selfserviceapi.deploypipeline.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gcp")
public record GcpProperties(
        String projectId,
        String region,
        String environment
) {

    public String serviceAccountEmail(String accountId) {
        return "%s-%s@%s.iam.gserviceaccount.com".formatted(accountId, environment, projectId);
    }

    public String serviceAccountResourceName(String accountId) {
        return "projects/%s/serviceAccounts/%s".formatted(projectId, serviceAccountEmail(accountId));
    }

    public String secretVersionName(String secretId) {
        return "projects/%s/secrets/%s-%s/versions/latest".formatted(projectId, secretId, environment);
    }

    /** One shared Artifact Registry repo for every POC, namespaced by slug then container name. */
    public String imageUri(String slug, String componentName, String versionLabel) {
        return "%s-docker.pkg.dev/%s/poc-images/%s/%s:%s".formatted(region, projectId, slug, componentName, versionLabel);
    }
}

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

    /**
     * One shared Artifact Registry repo for every POC, namespaced by slug and then by container
     * name — a manifest's containers each get their own image under the same POC. A repo with no
     * poc.yaml still resolves to a single container named "app" (see ManifestService's synthesized
     * default), so an unmanifested POC's image URI is unchanged from before this container name
     * existed as a parameter.
     */
    public String imageUri(String slug, String versionLabel, String containerName) {
        return "%s-docker.pkg.dev/%s/poc-images/%s/%s:%s".formatted(region, projectId, slug, containerName, versionLabel);
    }
}

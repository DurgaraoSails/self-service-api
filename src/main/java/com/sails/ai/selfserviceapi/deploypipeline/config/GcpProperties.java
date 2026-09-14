package com.sails.ai.selfserviceapi.deploypipeline.config;

import java.util.Locale;
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
     * The Secret Manager id backing one container's required variable, derived from
     * {@code (slug, container, key)} rather than accepted from the manifest.
     *
     * <p>Deriving it is what makes the binding safe. An author-supplied id would force a POC team to
     * know Secret Manager exists, would couple a portable repository to one GCP project, and — the
     * reason that actually decides it — would let one POC name another POC's secret, a cross-tenant
     * read the platform would then have to police. A derived id is collision-proof by construction
     * and prefix-queryable ({@code poc-<slug>-}) for cleanup.
     *
     * <p>The {@code -<environment>} suffix matches {@link #secretVersionName}'s convention so a dev
     * and a prod deployment of the same slug do not collide in a shared project.
     */
    public String pocSecretId(String slug, String containerName, String envKey) {
        return "poc-%s-%s-%s-%s".formatted(slug, containerName,
                envKey.toLowerCase(Locale.ROOT).replace('_', '-'), environment);
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

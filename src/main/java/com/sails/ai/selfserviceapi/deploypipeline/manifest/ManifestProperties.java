package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Operational knobs for manifest validation — platform conventions, not code logic, so they can
 * change without a redeploy of self-service-api itself. Kept to only what today's platform can
 * actually enforce or inject; see poc-manifest-deployment plan for what was deliberately left out
 * (e.g. a DATABASE_URL reservation for a per-POC database that doesn't exist yet).
 */
@ConfigurationProperties(prefix = "manifest")
public record ManifestProperties(

        /** Env var names a container's manifest entry may never set — the platform owns these. */
        List<String> reservedEnvNames,

        /** Env var name prefixes a container's manifest entry may never set — same reason. */
        List<String> reservedEnvPrefixes,

        /** Cloud Run's own per-service container limit, checked here rather than surfacing as a gcloud error. */
        int maxContainers
) {

    public ManifestProperties {
        if (reservedEnvNames == null) {
            reservedEnvNames = List.of("PORT", "POC_SLUG");
        }
        if (reservedEnvPrefixes == null) {
            reservedEnvPrefixes = List.of("SAILS_", "SVC_");
        }
        if (maxContainers <= 0) {
            maxContainers = 8;
        }
    }
}

package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import java.util.List;
import java.util.Map;

/**
 * One container's settings read from a {@code gcloud run deploy}/{@code --container} segment.
 * {@code env} holds only plain, non-secret values (already through {@code EnvVarClassifier});
 * {@code secretEnvNames} holds the names {@code --set-secrets} declared — never the Secret Manager
 * reference they pointed at, which names this platform's own secret, not the source repo's.
 */
public record ImportedContainer(
        String name,
        String dockerfile,
        String context,
        Integer port,
        String health,
        String cpu,
        String memory,
        Map<String, String> env,
        List<String> secretEnvNames
) {
}

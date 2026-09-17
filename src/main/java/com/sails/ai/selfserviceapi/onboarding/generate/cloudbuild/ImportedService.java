package com.sails.ai.selfserviceapi.onboarding.generate.cloudbuild;

import java.util.List;

/** One {@code gcloud run deploy} call — a Cloud Run service, made of one or more containers. */
public record ImportedService(
        String name,
        List<ImportedContainer> containers,
        Integer minInstances,
        Integer maxInstances,
        Boolean allowUnauthenticated
) {
}

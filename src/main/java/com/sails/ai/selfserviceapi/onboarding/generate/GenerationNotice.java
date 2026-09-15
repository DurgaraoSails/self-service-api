package com.sails.ai.selfserviceapi.onboarding.generate;

import com.sails.ai.selfserviceapi.generated.model.PocOnboardingSeverity;

/**
 * One thing worth a team's attention that is not a manifest validation violation — an import
 * decision, a lint finding, a truncated tree, why generation was unavailable. See
 * {@link GenerationNoticeCode} for the vocabulary and {@code PocGenerationNotice} in the OpenAPI
 * spec for the wire shape this maps onto 1:1.
 *
 * @param path      the file the notice is about, when there is one specific file — a Dockerfile
 *                  path, a cloudbuild.yaml path. Null for a repository- or container-level notice.
 * @param container the container the notice is about, when there is one. Null otherwise.
 */
public record GenerationNotice(PocOnboardingSeverity severity, String code, String message, String path, String container) {

    public static GenerationNotice error(String code, String message) {
        return new GenerationNotice(PocOnboardingSeverity.ERROR, code, message, null, null);
    }

    public static GenerationNotice warning(String code, String message) {
        return new GenerationNotice(PocOnboardingSeverity.WARNING, code, message, null, null);
    }

    public static GenerationNotice info(String code, String message) {
        return new GenerationNotice(PocOnboardingSeverity.INFO, code, message, null, null);
    }

    public GenerationNotice withPath(String newPath) {
        return new GenerationNotice(severity, code, message, newPath, container);
    }

    public GenerationNotice withContainer(String newContainer) {
        return new GenerationNotice(severity, code, message, path, newContainer);
    }
}

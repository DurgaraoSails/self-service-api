package com.sails.ai.selfserviceapi.onboarding.generate.model;

/**
 * A {@link ManifestDraftModel} could not produce a draft — an empty/malformed response, or a
 * provider-specific failure an adapter chooses to surface rather than let a raw
 * {@code RestClientException} escape. Unchecked: every caller in {@code onboarding.generate}
 * already has to handle "the model is unavailable" as a normal outcome, not an exceptional one.
 */
public class ManifestDraftException extends RuntimeException {

    public ManifestDraftException(String message) {
        super(message);
    }

    public ManifestDraftException(String message, Throwable cause) {
        super(message, cause);
    }
}

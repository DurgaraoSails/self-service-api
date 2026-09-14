package com.sails.ai.selfserviceapi.onboarding.generate;

import java.util.List;

/**
 * The model's draft still failed {@code ManifestValidator} after every repair attempt was spent.
 * Never reaches a user as a manifest — {@code PocManifestGenerationService} catches this and
 * returns the degraded outcome (the checker's findings plus the shipped template) instead. A
 * generated manifest that fails validation must never reach a user; this exception is how that
 * guarantee is enforced at the one call site that could otherwise violate it.
 */
public class ManifestDraftValidationException extends RuntimeException {

    private final List<String> violations;

    public ManifestDraftValidationException(List<String> violations) {
        super("Generated manifest still failed validation after repair attempts were exhausted: "
                + String.join("; ", violations));
        this.violations = violations;
    }

    public List<String> violations() {
        return violations;
    }
}

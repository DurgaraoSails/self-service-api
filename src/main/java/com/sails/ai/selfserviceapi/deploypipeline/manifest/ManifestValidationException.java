package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Thrown with every violation {@link ManifestValidator} found, not just the first — a bad
 * poc.yaml should tell its author everything wrong with it in one pass, not one violation per
 * retry.
 */
public class ManifestValidationException extends ApiException {

    private final List<String> violations;

    public ManifestValidationException(List<String> violations) {
        super(HttpStatus.BAD_REQUEST, "MANIFEST_VALIDATION_ERROR",
                "poc.yaml is invalid: " + String.join("; ", violations));
        this.violations = violations;
    }

    public List<String> getViolations() {
        return violations;
    }
}

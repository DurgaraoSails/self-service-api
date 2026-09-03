package com.sails.ai.selfserviceapi.deploypipeline.manifest;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Thrown when a POC repo's poc.yaml fails validation. Collects every violation into one message,
 * not just the first — an admin fixing a bad manifest deserves the whole list in one deploy
 * attempt, not one error per retry.
 */
public class InvalidPocManifestException extends ApiException {

    public InvalidPocManifestException(List<String> violations) {
        super(HttpStatus.BAD_REQUEST, "INVALID_POC_MANIFEST",
                "poc.yaml is invalid:\n- " + String.join("\n- ", violations));
    }
}

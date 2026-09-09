package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * Thrown by "deploy new version" when the repository's tags (and this POC's own version history)
 * contain nothing semver-parseable to derive the next label from. Existing tags remain deployable
 * via {@code deployExistingTag} — this only blocks the platform from inventing a number into a
 * repository that clearly numbers its releases some other way. See
 * docs/specs/poc-tag-driven-deployment.md, "Deriving the next tag name."
 */
public class NonSemverRepositoryException extends ApiException {

    public NonSemverRepositoryException(UUID pocId) {
        super(HttpStatus.CONFLICT, "NON_SEMVER_REPOSITORY",
                "POC " + pocId + "'s repository has existing tags, but none are semver (e.g. '1.2.3'), so a next "
                        + "version cannot be derived. Create a semver tag on the repository to continue, or deploy "
                        + "one of the existing tags directly.");
    }
}

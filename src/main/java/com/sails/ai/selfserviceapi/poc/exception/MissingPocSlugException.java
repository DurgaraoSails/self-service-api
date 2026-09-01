package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A POC can't be deployed without a slug: it is the Cloud Run service name and the Artifact
 * Registry path segment, and neither can be safely derived from a display name that may change.
 * POCs created before the deploy pipeline existed have none — set one via PUT /pocs/{id}.
 */
public class MissingPocSlugException extends ApiException {

    public MissingPocSlugException(Long pocId) {
        super(HttpStatus.CONFLICT, "POC_SLUG_REQUIRED",
                "POC " + pocId + " has no slug. Set one before deploying — it names the deployed service.");
    }
}

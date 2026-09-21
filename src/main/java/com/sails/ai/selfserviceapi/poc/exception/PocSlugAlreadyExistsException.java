package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The slug names a Cloud Run service and Artifact Registry path, so it has to stay globally
 * unique — thrown before the save reaches the database's own {@code UNIQUE} constraint, which
 * would otherwise surface as a bare, unhelpful {@code DataIntegrityViolationException} (see
 * {@code GlobalExceptionHandler.handleDataIntegrityViolation}).
 */
public class PocSlugAlreadyExistsException extends ApiException {

    public PocSlugAlreadyExistsException(String slug) {
        super(HttpStatus.CONFLICT, "POC_SLUG_ALREADY_EXISTS", "A POC with slug '" + slug + "' already exists.");
    }
}

package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PocSlugAlreadyExistsException extends ApiException {

    public PocSlugAlreadyExistsException(String slug) {
        super(HttpStatus.CONFLICT, "POC_SLUG_ALREADY_EXISTS", "A POC with the slug '" + slug + "' already exists.");
    }
}

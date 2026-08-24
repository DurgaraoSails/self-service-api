package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PocNotFoundException extends ApiException {

    public PocNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "POC_NOT_FOUND", "POC not found: " + id);
    }
}

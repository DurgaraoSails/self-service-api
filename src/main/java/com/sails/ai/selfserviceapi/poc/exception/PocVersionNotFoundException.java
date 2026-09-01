package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class PocVersionNotFoundException extends ApiException {

    public PocVersionNotFoundException(Long id) {
        super(HttpStatus.NOT_FOUND, "POC_VERSION_NOT_FOUND", "POC version not found: " + id);
    }
}

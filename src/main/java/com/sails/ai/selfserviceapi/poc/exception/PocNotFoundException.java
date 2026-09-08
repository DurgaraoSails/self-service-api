package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PocNotFoundException extends ApiException {

    public PocNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "POC_NOT_FOUND", "POC not found: " + id);
    }

    public PocNotFoundException(String slug) {
        super(HttpStatus.NOT_FOUND, "POC_NOT_FOUND", "POC not found: " + slug);
    }
}

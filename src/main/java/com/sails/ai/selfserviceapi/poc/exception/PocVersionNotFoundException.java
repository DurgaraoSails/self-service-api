package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class PocVersionNotFoundException extends ApiException {

    public PocVersionNotFoundException(UUID id) {
        super(HttpStatus.NOT_FOUND, "POC_VERSION_NOT_FOUND", "POC version not found: " + id);
    }
}

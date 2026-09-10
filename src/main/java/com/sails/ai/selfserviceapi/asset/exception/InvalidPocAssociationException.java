package com.sails.ai.selfserviceapi.asset.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidPocAssociationException extends ApiException {

    public InvalidPocAssociationException(String message) {
        super(HttpStatus.CONFLICT, "INVALID_POC_ASSOCIATION", message);
    }
}

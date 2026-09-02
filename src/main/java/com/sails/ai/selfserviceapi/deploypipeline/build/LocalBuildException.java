package com.sails.ai.selfserviceapi.deploypipeline.build;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class LocalBuildException extends ApiException {

    public LocalBuildException(String message) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "LOCAL_BUILD_FAILED", message);
    }

    public LocalBuildException(String message, Throwable cause) {
        super(HttpStatus.INTERNAL_SERVER_ERROR, "LOCAL_BUILD_FAILED", message);
        initCause(cause);
    }
}

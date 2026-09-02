package com.sails.ai.selfserviceapi.deploypipeline.build;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CloudBuildApiException extends ApiException {

    public CloudBuildApiException(String message) {
        super(HttpStatus.BAD_GATEWAY, "CLOUD_BUILD_API_ERROR", message);
    }

    public CloudBuildApiException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "CLOUD_BUILD_API_ERROR", message);
        initCause(cause);
    }
}

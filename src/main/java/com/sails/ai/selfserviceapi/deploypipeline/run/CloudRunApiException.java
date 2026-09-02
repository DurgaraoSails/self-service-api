package com.sails.ai.selfserviceapi.deploypipeline.run;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class CloudRunApiException extends ApiException {

    public CloudRunApiException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "CLOUD_RUN_API_ERROR", message);
        initCause(cause);
    }
}

package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Thrown when the pipeline reports SUCCEEDED without a hostedUrl — "active" always means "reachable". */
public class MissingHostedUrlException extends ApiException {

    public MissingHostedUrlException(UUID deploymentId) {
        super(HttpStatus.BAD_REQUEST, "MISSING_HOSTED_URL",
                "hostedUrl is required when reporting SUCCEEDED for deployment " + deploymentId + ".");
    }
}

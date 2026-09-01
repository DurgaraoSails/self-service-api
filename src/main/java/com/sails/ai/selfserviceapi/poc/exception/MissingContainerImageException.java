package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Thrown when the pipeline reports SUCCEEDED for a build without a containerImage. */
public class MissingContainerImageException extends ApiException {

    public MissingContainerImageException(UUID deploymentId) {
        super(HttpStatus.BAD_REQUEST, "MISSING_CONTAINER_IMAGE",
                "containerImage is required when reporting SUCCEEDED for deployment " + deploymentId + ".");
    }
}

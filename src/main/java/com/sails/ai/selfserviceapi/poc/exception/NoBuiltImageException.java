package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown when rolling back to a version that was never successfully built. */
public class NoBuiltImageException extends ApiException {

    public NoBuiltImageException(Long versionId) {
        super(HttpStatus.CONFLICT, "NO_BUILT_IMAGE", "Version " + versionId + " was never successfully built — nothing to redeploy.");
    }
}

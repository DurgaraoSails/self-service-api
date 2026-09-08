package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/** Thrown when rolling back to a version that was never successfully built. */
public class NoBuiltImageException extends ApiException {

    public NoBuiltImageException(UUID versionId) {
        super(HttpStatus.CONFLICT, "NO_BUILT_IMAGE", "Version " + versionId + " was never successfully built — nothing to redeploy.");
    }
}

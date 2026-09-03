package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * The POC exists and the user is entitled to it, but it has never been deployed — {@code appUrl}
 * is null, so there is nothing to frame. A 409 rather than a 404: the POC is real and the caller
 * did nothing wrong, which is a different thing for the portal to say than "no such POC".
 */
public class PocNotLaunchableException extends ApiException {

    public PocNotLaunchableException(String slug) {
        super(HttpStatus.CONFLICT, "POC_NOT_LAUNCHABLE",
                "POC " + slug + " has no deployed application to launch yet.");
    }
}

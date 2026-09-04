package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Thrown by POST /pocs/{slug}/launch when the POC has no appUrl yet — never deployed. */
public class PocNotLaunchableException extends ApiException {

    public PocNotLaunchableException(String slug) {
        super(HttpStatus.CONFLICT, "POC_NOT_LAUNCHABLE", "POC has no app URL yet: " + slug);
    }
}

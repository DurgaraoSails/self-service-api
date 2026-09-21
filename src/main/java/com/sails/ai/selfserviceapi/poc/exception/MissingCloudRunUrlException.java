package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

/**
 * A POC in SELF deployment mode is launched straight off its own {@code appUrl} — there is no
 * pipeline to eventually fill it in, so it has to be supplied at create/update time or the POC can
 * never be launched at all.
 */
public class MissingCloudRunUrlException extends ApiException {

    public MissingCloudRunUrlException() {
        super(HttpStatus.BAD_REQUEST, "MISSING_CLOUD_RUN_URL",
                "appUrl (the Cloud Run URL) is required when deploymentMode is SELF.");
    }
}

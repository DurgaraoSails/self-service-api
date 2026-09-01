package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class MissingGithubUrlException extends ApiException {

    public MissingGithubUrlException(Long pocId) {
        super(HttpStatus.BAD_REQUEST, "MISSING_GITHUB_URL", "POC " + pocId + " has no githubUrl to build from.");
    }
}

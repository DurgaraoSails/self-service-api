package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class MissingGithubUrlException extends ApiException {

    public MissingGithubUrlException(UUID pocId) {
        super(HttpStatus.BAD_REQUEST, "MISSING_GITHUB_URL", "POC " + pocId + " has no githubUrl to build from.");
    }
}

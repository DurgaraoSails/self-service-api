package com.sails.ai.selfserviceapi.deployment.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import org.springframework.http.HttpStatus;

public class GitHubApiException extends ApiException {

    public GitHubApiException(String message) {
        super(HttpStatus.BAD_GATEWAY, "GITHUB_API_ERROR", message);
    }

    public GitHubApiException(String message, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "GITHUB_API_ERROR", message);
        initCause(cause);
    }
}

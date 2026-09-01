package com.sails.ai.selfserviceapi.poc.exception;

import com.sails.ai.selfserviceapi.common.exception.ApiException;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class DeploymentAlreadyTerminalException extends ApiException {

    public DeploymentAlreadyTerminalException(UUID id) {
        super(HttpStatus.CONFLICT, "DEPLOYMENT_ALREADY_TERMINAL", "Deployment " + id + " already reached a terminal status.");
    }
}
